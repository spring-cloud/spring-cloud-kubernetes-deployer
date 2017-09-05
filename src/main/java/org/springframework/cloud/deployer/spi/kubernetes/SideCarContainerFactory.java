/*
 * Copyright 2017 the original author or authors.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.springframework.cloud.deployer.spi.kubernetes;

import static org.springframework.cloud.deployer.spi.kubernetes.KubernetesDeployerProperties.SideCar;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.util.Assert;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * @author David Turanski
 **/
public class SideCarContainerFactory {
	private static Log logger = LogFactory.getLog(SideCarContainerFactory.class);

	public Container create(String name, SideCar sideCar) {
		String image;
		try {
			image = sideCar.getImage().getURI().getSchemeSpecificPart();
		}
		catch (IOException e) {
			throw new IllegalArgumentException("Unable to get URI for " + sideCar.getImage(), e);
		}
		logger.debug(String.format("Creating sideCar container %s from image %s", name, image));

		List<EnvVar> envVars = new ArrayList<>();
		for (String envVar : sideCar.getEnvironmentVariables()) {
			String[] strings = envVar.split("=", 2);
			Assert.isTrue(strings.length == 2, "Invalid environment variable declared: " + envVar);
			envVars.add(new EnvVar(strings[0], strings[1], null));
		}

		List<String> appArgs = new LinkedList<>();

		return new ContainerBuilder().withName(name).withImage(image).withEnv(envVars).withArgs(appArgs)
			.withVolumeMounts(sideCar.getVolumeMounts()).build();
	}

}

