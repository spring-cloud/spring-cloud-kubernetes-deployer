/*
 * Copyright 2015-2016 the original author or authors.
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

package org.springframework.cloud.deployer.spi.kubernetes;


import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.task.TaskStatus;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Quantity;

/**
 * Abstract base class for a deployer that targets Kubernetes.
 *
 * @author Florian Rosenberg
 * @author Thomas Risberg
 */
public class AbstractKubernetesDeployer {

	protected static final String SPRING_DEPLOYMENT_KEY = "spring-deployment-id";
	protected static final String SPRING_GROUP_KEY = "spring-group-id";
	protected static final String SPRING_APP_KEY = "spring-app-id";
	protected static final String SPRING_MARKER_KEY = "role";
	protected static final String SPRING_MARKER_VALUE = "spring-app";

	protected static final Logger logger = LoggerFactory.getLogger(AbstractKubernetesDeployer.class);

	/**
	 * Creates a map of labels for a given ID. This will allow Kubernetes services
	 * to "select" the right ReplicationControllers.
	 */
	Map<String, String> createIdMap(String appId, AppDeploymentRequest request) {
		//TODO: handling of app and group ids
		Map<String, String> map = new HashMap<>();
		map.put(SPRING_APP_KEY, appId);
		String groupId = request.getEnvironmentProperties().get(AppDeployer.GROUP_PROPERTY_KEY);
		if (groupId != null) {
			map.put(SPRING_GROUP_KEY, groupId);
		}
		map.put(SPRING_DEPLOYMENT_KEY, createDeploymentId(request));
		return map;
	}

	String createDeploymentId(AppDeploymentRequest request) {
		String groupId = request.getEnvironmentProperties().get(AppDeployer.GROUP_PROPERTY_KEY);
		String deploymentId;
		if (groupId == null) {
			deploymentId = String.format("%s", request.getDefinition().getName());
		}
		else {
			deploymentId = String.format("%s-%s", groupId, request.getDefinition().getName());
		}
		// Kubernetes does not allow . in the name
		return deploymentId.replace('.', '-');
	}

	AppStatus buildAppStatus(KubernetesDeployerProperties properties, String id, PodList list) {
		AppStatus.Builder statusBuilder = AppStatus.of(id);

		if (list == null) {
			statusBuilder.with(new KubernetesAppInstanceStatus(id, null, properties));
		} else {
			for (Pod pod : list.getItems()) {
				statusBuilder.with(new KubernetesAppInstanceStatus(id, pod, properties));
			}
		}
		return statusBuilder.build();
	}

	Map<String, Quantity> deduceResourceLimits(KubernetesDeployerProperties properties, AppDeploymentRequest request) {
		String memOverride = request.getEnvironmentProperties().get("spring.cloud.deployer.kubernetes.memory");
		if (memOverride == null)
			memOverride = properties.getMemory();

		String cpuOverride = request.getEnvironmentProperties().get("spring.cloud.deployer.kubernetes.cpu");
		if (cpuOverride == null)
			cpuOverride = properties.getCpu();

		logger.debug("Using limits - cpu: " + cpuOverride + " mem: " + memOverride);

		Map<String,Quantity> limits = new HashMap<String,Quantity>();
		limits.put("memory", new Quantity(memOverride));
		limits.put("cpu", new Quantity(cpuOverride));
		return limits;
	}

}
