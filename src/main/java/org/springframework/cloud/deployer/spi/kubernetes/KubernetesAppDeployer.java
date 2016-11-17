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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.bind.YamlConfigurationFactory;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.ReplicationController;
import io.fabric8.kubernetes.api.model.ReplicationControllerBuilder;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServiceSpecBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.util.StringUtils;

/**
 * A deployer that targets Kubernetes.
 *
 * @author Florian Rosenberg
 * @author Thomas Risberg
 * @author Mark Fisher
 * @author Donovan Muller
 */
public class KubernetesAppDeployer extends AbstractKubernetesDeployer implements AppDeployer {

	private static final String SERVER_PORT_KEY = "server.port";

	private KubernetesDeployerProperties properties = new KubernetesDeployerProperties();

	private final KubernetesClient client;

	private final ContainerFactory containerFactory;

	@Autowired
	public KubernetesAppDeployer(KubernetesDeployerProperties properties,
	                             KubernetesClient client) {
		this(properties, client, new DefaultContainerFactory(properties));
	}

	@Autowired
	public KubernetesAppDeployer(KubernetesDeployerProperties properties,
	                             KubernetesClient client, ContainerFactory containerFactory) {
		this.properties = properties;
		this.client = client;
		this.containerFactory = containerFactory;
	}

	@Override
	public String deploy(AppDeploymentRequest request) {

		String appId = createDeploymentId(request);
		logger.debug(String.format("Deploying app: %s", appId));

		try {
			AppStatus status = status(appId);
			if (!status.getState().equals(DeploymentState.unknown)) {
				throw new IllegalStateException(String.format("App '%s' is already deployed", appId));
			}

			int externalPort = configureExternalPort(request);

			String countProperty = request.getDeploymentProperties().get(COUNT_PROPERTY_KEY);
			int count = (countProperty != null) ? Integer.parseInt(countProperty) : 1;

			String indexedProperty = request.getDeploymentProperties().get(INDEXED_PROPERTY_KEY);
			boolean indexed = (indexedProperty != null) ? Boolean.valueOf(indexedProperty).booleanValue() : false;

			if (indexed) {
				for (int index=0 ; index < count ; index++) {
					String indexedId = appId + "-" + index;
					Map<String, String> idMap = createIdMap(appId, request, index);
					logger.debug(String.format("Creating service: %s on %d with index %d", appId, externalPort, index));
					createService(indexedId, request, idMap, externalPort);
					logger.debug(String.format("Creating repl controller: %s with index %d", appId, index));
					createReplicationController(indexedId, request, idMap, externalPort, 1, index);
				}
			}
			else {
				Map<String, String> idMap = createIdMap(appId, request, null);
				logger.debug(String.format("Creating service: %s on {}", appId, externalPort));
				createService(appId, request, idMap, externalPort);
				logger.debug(String.format("Creating repl controller: %s", appId));
				createReplicationController(appId, request, idMap, externalPort, count, null);
			}

			return appId;
		} catch (RuntimeException e) {
			logger.error(e.getMessage(), e);
			throw e;
		}
	}

	@Override
	public void undeploy(String appId) {
		logger.debug(String.format("Undeploying app: %s", appId));

		List<ReplicationController> apps =
			client.replicationControllers().withLabel(SPRING_APP_KEY, appId).list().getItems();
		for (ReplicationController rc : apps) {
			String appIdToDelete = rc.getMetadata().getName();
			logger.debug(String.format("Deleting svc, rc and pods for: %s", appIdToDelete));

			Service svc = client.services().withName(appIdToDelete).get();
			try {
				if (svc != null && "LoadBalancer".equals(svc.getSpec().getType())) {
					int tries = 0;
					int maxWait = properties.getMinutesToWaitForLoadBalancer() * 6; // we check 6 times per minute
					while (tries++ < maxWait) {
						if (svc.getStatus() != null && svc.getStatus().getLoadBalancer() != null &&
								svc.getStatus().getLoadBalancer().getIngress() != null &&
								svc.getStatus().getLoadBalancer().getIngress().isEmpty()) {
							if (tries % 6 == 0) {
								logger.warn("Waiting for LoadBalancer to complete before deleting it ...");
							}
							logger.debug(String.format("Waiting for LoadBalancer, try %d", tries));
							try {
								Thread.sleep(10000L);
							} catch (InterruptedException e) {
							}
							svc = client.services().withName(appIdToDelete).get();
						} else {
							break;
						}
					}
					logger.debug(String.format("LoadBalancer Ingress: %s",
							svc.getStatus().getLoadBalancer().getIngress().toString()));
				}
				Boolean svcDeleted = client.services().withName(appIdToDelete).delete();
				logger.debug(String.format("Deleted service for: %s %b", appIdToDelete, svcDeleted));
				Boolean rcDeleted = client.replicationControllers().withName(appIdToDelete).delete();
				logger.debug(String.format("Deleted replication controller for: %s %b", appIdToDelete, rcDeleted));
				Map<String, String> selector = new HashMap<>();
				selector.put(SPRING_APP_KEY, appIdToDelete);
				Boolean podDeleted = client.pods().withLabels(selector).delete();
				logger.debug(String.format("Deleted pods for: %s %b", appIdToDelete, podDeleted));
			} catch (RuntimeException e) {
				logger.error(e.getMessage(), e);
				throw e;
			}
		}
	}

	@Override
	public AppStatus status(String appId) {
		Map<String, String> selector = new HashMap<>();
		selector.put(SPRING_APP_KEY, appId);
		PodList list = client.pods().withLabels(selector).list();
		if (logger.isDebugEnabled()) {
			logger.debug(String.format("Building AppStatus for app: %s", appId));
			logger.debug(String.format("Pods for appId %s: %d", appId, list.getItems().size()));
			for (Pod pod : list.getItems()) {
				logger.debug(String.format("Pod: %s", pod.getMetadata().getName()));
			}
		}
		AppStatus status = buildAppStatus(properties, appId, list);
		logger.debug(String.format("Status for app: %s is %s", appId, status));

		return status;
	}

	protected int configureExternalPort(final AppDeploymentRequest request) {
		int externalPort = 8080;
		Map<String, String> parameters = request.getDefinition().getProperties();
		if (parameters.containsKey(SERVER_PORT_KEY)) {
			externalPort = Integer.valueOf(parameters.get(SERVER_PORT_KEY));
		}

		return externalPort;
	}

	protected String createDeploymentId(AppDeploymentRequest request) {
		String groupId = request.getDeploymentProperties().get(AppDeployer.GROUP_PROPERTY_KEY);
		String deploymentId;
		if (groupId == null) {
			deploymentId = String.format("%s", request.getDefinition().getName());
		}
		else {
			deploymentId = String.format("%s-%s", groupId, request.getDefinition().getName());
		}
		// Kubernetes does not allow . in the name and does not allow uppercase in the name
		return deploymentId.replace('.', '-').toLowerCase();
	}

	private ReplicationController createReplicationController(
			String appId, AppDeploymentRequest request,
			Map<String, String> idMap, int externalPort, int replicas, Integer instanceIndex) {
		ReplicationController rc = new ReplicationControllerBuilder()
				.withNewMetadata()
					.withName(appId)
					.withLabels(idMap)
						.addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE)
				.endMetadata()
				.withNewSpec()
					.withReplicas(replicas)
					.withSelector(idMap)
					.withNewTemplate()
						.withNewMetadata()
							.withLabels(idMap)
								.addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE)
						.endMetadata()
						.withSpec(createPodSpec(appId, request, Integer.valueOf(externalPort), instanceIndex))
					.endTemplate()
				.endSpec()
				.build();

		return client.replicationControllers().create(rc);
	}

	protected PodSpec createPodSpec(String appId, AppDeploymentRequest request, Integer port, Integer instanceIndex) {
		PodSpecBuilder podSpec = new PodSpecBuilder();

		// Add image secrets if set
		if (properties.getImagePullSecret() != null) {
			podSpec.addNewImagePullSecret(properties.getImagePullSecret());
		}

		Container container = containerFactory.create(appId, request, port, instanceIndex);

		// add memory and cpu resource limits
		ResourceRequirements req = new ResourceRequirements();
		req.setLimits(deduceResourceLimits(properties, request));
		req.setRequests(deduceResourceRequests(properties, request));
		container.setResources(req);
		ImagePullPolicy pullPolicy = deduceImagePullPolicy(properties, request);
		container.setImagePullPolicy(pullPolicy.name());

		// only add volumes with corresponding volume mounts
		podSpec.withVolumes(getVolumes(properties, request).stream()
				.filter(volume -> container.getVolumeMounts().stream()
						.anyMatch(volumeMount -> volumeMount.getName().equals(volume.getName())))
				.collect(Collectors.toList()));

		podSpec.addToContainers(container);
		return podSpec.build();
	}

	/**
	 * Volume deployment properties are specified in YAML format:
	 *
	 * <code>
	 *     spring.cloud.deployer.kubernetes.volumes=[{name: testhostpath, hostPath: { path: '/test/override/hostPath' }},
	 *     	{name: 'testpvc', persistentVolumeClaim: { claimName: 'testClaim', readOnly: 'true' }},
	 *     	{name: 'testnfs', nfs: { server: '10.0.0.1:111', path: '/test/nfs' }}]
	 * </code>
	 *
	 * Volumes can be specified as deployer properties as well as app deployment properties.
	 * Deployment properties override deployer properties.
	 *
	 * @param request
	 * @return the configured volumes
	 */
	private List<Volume> getVolumes(KubernetesDeployerProperties properties, AppDeploymentRequest request) {
		List<Volume> volumes = new ArrayList<>();

		String volumeDeploymentProperty = request.getDeploymentProperties()
				.getOrDefault("spring.cloud.deployer.kubernetes.volumes", "");
		if (!StringUtils.isEmpty(volumeDeploymentProperty)) {
			YamlConfigurationFactory<KubernetesDeployerProperties> volumeYamlConfigurationFactory =
					new YamlConfigurationFactory<>(KubernetesDeployerProperties.class);
			volumeYamlConfigurationFactory.setYaml(volumeDeploymentProperty);
			try {
				volumeYamlConfigurationFactory.afterPropertiesSet();
				volumes.addAll(
						volumeYamlConfigurationFactory.getObject().getVolumes());
			}
			catch (Exception e) {
				throw new IllegalArgumentException(
						String.format("Invalid volume '%s'", volumeDeploymentProperty), e);
			}
		}
		// only add volumes that have not already been added, based on the volume's name
		// i.e. allow provided deployment volumes to override deployer defined volumes
		volumes.addAll(properties.getVolumes().stream()
				.filter(volume -> volumes.stream()
						.noneMatch(existingVolume -> existingVolume.getName().equals(volume.getName())))
				.collect(Collectors.toList()));

		return volumes;
	}

	private void createService(String appId, AppDeploymentRequest request, Map<String, String> idMap, int externalPort) {
		ServiceSpecBuilder spec = new ServiceSpecBuilder();
		boolean isCreateLoadBalancer = false;
		String createLoadBalancer = request.getDeploymentProperties().get("spring.cloud.deployer.kubernetes.createLoadBalancer");
		String createNodePort = request.getDeploymentProperties().get("spring.cloud.deployer.kubernetes.createNodePort");

		if (createLoadBalancer != null && createNodePort != null) {
			throw new IllegalArgumentException("Cannot create NodePort and LoadBalancer at the same time.");
		}

		if (createLoadBalancer == null) {
			isCreateLoadBalancer = properties.isCreateLoadBalancer();
		}
		else {
			if ("true".equals(createLoadBalancer.toLowerCase())) {
				isCreateLoadBalancer = true;
			}
		}

		if (isCreateLoadBalancer) {
			spec.withType("LoadBalancer");
		}

		ServicePort servicePort = new ServicePort();
		servicePort.setPort(externalPort);

		if (createNodePort != null) {
			spec.withType("NodePort");
			if (!"true".equals(createNodePort.toLowerCase())) {
				try {
					Integer nodePort = Integer.valueOf(createNodePort);
					servicePort.setNodePort(nodePort);
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException(String.format("Invalid value: %s: provided port is not valid.", createNodePort));
				}
			}
		}

		spec.withSelector(idMap)
			.addNewPortLike(servicePort).endPort();

		client.services().inNamespace(client.getNamespace()).createNew()
				.withNewMetadata()
					.withName(appId)
					.withLabels(idMap)
					.addToLabels(SPRING_MARKER_KEY, SPRING_MARKER_VALUE)
					.endMetadata()
				.withSpec(spec.build())
				.done();
	}

}
