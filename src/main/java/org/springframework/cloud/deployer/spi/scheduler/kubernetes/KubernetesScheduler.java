/*
 * Copyright 2018-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.deployer.spi.scheduler.kubernetes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.StatusCause;
import io.fabric8.kubernetes.api.model.batch.CronJob;
import io.fabric8.kubernetes.api.model.batch.CronJobBuilder;
import io.fabric8.kubernetes.api.model.batch.CronJobList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;

import org.springframework.cloud.deployer.spi.kubernetes.AbstractKubernetesDeployer;
import org.springframework.cloud.deployer.spi.kubernetes.DefaultContainerFactory;
import org.springframework.cloud.deployer.spi.kubernetes.support.DeploymentPropertiesResolver;
import org.springframework.cloud.deployer.spi.scheduler.CreateScheduleException;
import org.springframework.cloud.deployer.spi.scheduler.ScheduleInfo;
import org.springframework.cloud.deployer.spi.scheduler.ScheduleRequest;
import org.springframework.cloud.deployer.spi.scheduler.Scheduler;
import org.springframework.cloud.deployer.spi.scheduler.SchedulerException;
import org.springframework.cloud.deployer.spi.scheduler.SchedulerPropertyKeys;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * Kubernetes implementation of the {@link Scheduler} SPI.
 *
 * @author Chris Schaefer
 * @author Ilayaperumal Gopinathan
 */
public class KubernetesScheduler extends AbstractKubernetesDeployer implements Scheduler {
	private static final String SPRING_CRONJOB_ID_KEY = "spring-cronjob-id";

	private static final String SCHEDULE_EXPRESSION_FIELD_NAME = "spec.schedule";

	public KubernetesScheduler(KubernetesClient client,
			KubernetesSchedulerProperties properties) {
		Assert.notNull(client, "KubernetesClient must not be null");
		Assert.notNull(properties, "KubernetesSchedulerProperties must not be null");

		this.client = client;
		this.properties = properties;
		this.containerFactory = new DefaultContainerFactory(properties);
		this.deploymentPropertiesResolver = new DeploymentPropertiesResolver("spring.cloud.scheduler.", properties);
	}

	@Override
	public void schedule(ScheduleRequest scheduleRequest) {
		if(scheduleRequest != null) {
			validateScheduleName(scheduleRequest);
		}
		try {
			createCronJob(scheduleRequest);
		}
		catch (KubernetesClientException e) {
			String invalidCronExceptionMessage = getExceptionMessageForField(e, SCHEDULE_EXPRESSION_FIELD_NAME);

			if (StringUtils.hasText(invalidCronExceptionMessage)) {
				throw new CreateScheduleException(invalidCronExceptionMessage, e);
			}

			throw new CreateScheduleException("Failed to create schedule " + scheduleRequest.getScheduleName(), e);
		}
	}

	public void validateScheduleName(ScheduleRequest request) {
		if(request.getScheduleName() == null) {
			throw new CreateScheduleException("The name for the schedule request is null", null);
		}
		if(!Pattern.matches("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$", request.getScheduleName())) {
			throw new CreateScheduleException("Invalid Format for Schedule Name. Schedule name can only contain lowercase letters, numbers 0-9 and hyphens.", null);
		}

	}

	@Override
	public void unschedule(String scheduleName) {
		boolean unscheduled = this.client.batch().cronjobs().withName(scheduleName).delete();

		if (!unscheduled) {
			throw new SchedulerException("Failed to unschedule schedule " + scheduleName + " does not exist.");
		}
	}

	@Override
	public List<ScheduleInfo> list(String taskDefinitionName) {
		return list()
				.stream()
				.filter(scheduleInfo -> taskDefinitionName.equals(scheduleInfo.getTaskDefinitionName()))
				.collect(Collectors.toList());
	}

	@Override
	public List<ScheduleInfo> list() {
		CronJobList cronJobList = this.client.batch().cronjobs().list();

		List<CronJob> cronJobs = cronJobList.getItems();
		List<ScheduleInfo> scheduleInfos = new ArrayList<>();

		for (CronJob cronJob : cronJobs) {
			if (cronJob.getMetadata() != null && cronJob.getMetadata().getLabels() != null &&
					StringUtils.hasText(cronJob.getMetadata().getLabels().get(SPRING_CRONJOB_ID_KEY))) {
				Map<String, String> properties = new HashMap<>();
				properties.put(SchedulerPropertyKeys.CRON_EXPRESSION, cronJob.getSpec().getSchedule());

				ScheduleInfo scheduleInfo = new ScheduleInfo();
				scheduleInfo.setScheduleName(cronJob.getMetadata().getName());
				scheduleInfo.setTaskDefinitionName(cronJob.getMetadata().getLabels().get(SPRING_CRONJOB_ID_KEY));
				scheduleInfo.setScheduleProperties(properties);

				scheduleInfos.add(scheduleInfo);
			}
		}

		return scheduleInfos;
	}

	protected CronJob createCronJob(ScheduleRequest scheduleRequest) {
		Map<String, String> labels = Collections.singletonMap(SPRING_CRONJOB_ID_KEY,
				scheduleRequest.getDefinition().getName());

		Map<String, String> schedulerProperties = scheduleRequest.getSchedulerProperties();
		String schedule = schedulerProperties.get(SchedulerPropertyKeys.CRON_EXPRESSION);
		Assert.hasText(schedule, "The property: " + SchedulerPropertyKeys.CRON_EXPRESSION + " must be defined");

		RestartPolicy restartPolicy = this.deploymentPropertiesResolver.getRestartPolicy(scheduleRequest.getSchedulerProperties());
		PodSpec podSpec = createPodSpec(scheduleRequest.getScheduleName(), scheduleRequest, null,
				(restartPolicy.equals(RestartPolicy.Never)) ? true : false);
		String taskServiceAccountName = this.deploymentPropertiesResolver.getTaskServiceAccountName(scheduleRequest.getSchedulerProperties());
		if (StringUtils.hasText(taskServiceAccountName)) {
			podSpec.setServiceAccountName(taskServiceAccountName);
		}

		CronJob cronJob = new CronJobBuilder().withNewMetadata().withName(scheduleRequest.getScheduleName())
				.withLabels(labels).endMetadata().withNewSpec().withSchedule(schedule).withNewJobTemplate()
				.withNewSpec().withNewTemplate().withSpec(podSpec).endTemplate().endSpec()
				.endJobTemplate().endSpec().build();

		setImagePullSecret(scheduleRequest, cronJob);

		return this.client.batch().cronjobs().create(cronJob);
	}

	protected String getExceptionMessageForField(KubernetesClientException clientException,
			String fieldName) {
		List<StatusCause> statusCauses = clientException.getStatus().getDetails().getCauses();

		if (!CollectionUtils.isEmpty(statusCauses)) {
			for (StatusCause statusCause : statusCauses) {
				if (fieldName.equals(statusCause.getField())) {
					return clientException.getStatus().getMessage();
				}
			}
		}

		return null;
	}

	private void setImagePullSecret(ScheduleRequest scheduleRequest, CronJob cronJob) {
		String imagePullSecret = KubernetesSchedulerPropertyResolver.getImagePullSecret(scheduleRequest,
				(KubernetesSchedulerProperties) this.properties);

		if (StringUtils.hasText(imagePullSecret)) {
			LocalObjectReference localObjectReference = new LocalObjectReference();
			localObjectReference.setName(imagePullSecret);

			cronJob.getSpec().getJobTemplate().getSpec().getTemplate().getSpec().getImagePullSecrets()
					.add(localObjectReference);
		}
	}
}
