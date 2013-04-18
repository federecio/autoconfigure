/** (C) Copyright 2013 Hal Hildebrand, All Rights Reserved
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 *     
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License.
 */
package com.hellblazer.autoconfigure;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.hellblazer.nexus.GossipScope;
import com.hellblazer.slp.InvalidSyntaxException;
import com.hellblazer.slp.ServiceEvent;
import com.hellblazer.slp.ServiceListener;
import com.hellblazer.slp.ServiceReference;
import com.hellblazer.slp.ServiceScope;
import com.hellblazer.slp.ServiceURL;
import com.hellblazer.utils.LabeledThreadFactory;
import com.hellblazer.utils.Rendezvous;
import com.hellblazer.utils.Utils;

/**
 * A simple way to hack around the configuration shit storm of hell that is the
 * current state of distributed systems.
 * 
 * @author hhildebrand
 * 
 */
public class AutoConfigure {
	private static final Logger logger = Logger.getLogger(AutoConfigure.class
			.getCanonicalName());

	public static String constructFilter(String service,
			Map<String, String> properties) {
		StringBuilder builder = new StringBuilder();
		builder.append('(');
		if (properties.size() != 0) {
			builder.append(" &(");
		}
		builder.append(String.format("%s=%s", ServiceScope.SERVICE_TYPE,
				service));
		if (properties.size() != 0) {
			builder.append(")");
		}
		for (Map.Entry<String, String> entry : properties.entrySet()) {
			builder.append(String.format(" (%s=%s) ", entry.getKey(),
					entry.getValue()));
		}
		builder.append(')');
		return builder.toString();
	}

	private final Map<String, String> additionalPorts = new HashMap<>();
	private final int addressIndex;
	private final AtomicReference<InetSocketAddress> bound = new AtomicReference<>();
	private final List<File> configurations;
	private final ServiceScope discovery;
	private final String hostVariable;
	private final String networkInterface;
	private final String portVariable;
	private final AtomicReference<Rendezvous> rendezvous = new AtomicReference<>();
	private final Map<String, String> runProperties = new HashMap<>();
	private final Map<ServiceListener, ServiceCollection> serviceCollections = new HashMap<>();
	private final String serviceFormat;
	private final Map<String, String> serviceProperties;
	private final AtomicReference<UUID> serviceRegistration = new AtomicReference<>();
	private final Map<ServiceListener, Service> services = new HashMap<>();
	private final Map<String, String> substitutions;
	private final List<File> transformedConfigurations = new ArrayList<>();
	private final List<UniqueDirectory> uniqueDirectories;

	/**
	 * Construct an instance from the configuration POJO
	 * 
	 * @param config
	 *            - the configuration to use
	 * @throws SocketException
	 *             - if the discovery service cannot be constructed
	 */
	public AutoConfigure(Configuration config) throws SocketException {
		this(config.serviceUrl, config.hostVariable, config.portVariable,
				config.networkInterface, config.addressIndex,
				config.serviceProperties, new GossipScope(
						config.gossip.construct()).start(), config.services,
				config.serviceCollections, config.configurations,
				config.substitutions, config.uniqueDirectories,
				config.additionalPorts);
	}

	/**
	 * Construct an instance
	 * 
	 * @param serviceFormat
	 *            - the format string which results in the service registration
	 *            URL
	 * @param hostVariable
	 *            - the name of the variable to substitute the host of this
	 *            service in the configuration files
	 * @param portVariable
	 *            - the name of the variable to substitute the allocated port of
	 *            this service in the configuation files
	 * @param networkInterface
	 *            - the network interface to use to bind this service
	 * @param addressIndex
	 *            - the index of the address to use that are bound to the
	 *            network interface
	 * @param serviceProperties
	 *            - the properties used to register this service in the
	 *            discovery scope
	 * @param discovery
	 *            - the service scope used for the auto configuration process
	 * @param serviceDefinitions
	 *            - the list of singular services that need to be discovered
	 * @param serviceCollectionDefinitions
	 *            - the list of service collections that need to be discovered
	 * @param configurations
	 *            - the list of files to process when all the required services
	 *            have been discovered
	 * @param substitutions
	 *            - an additional list of properties that will be substituted in
	 *            the configuration files
	 * @param uniqueDirectories
	 *            - a list of unique directories that will be created and used
	 *            when processing the configurations
	 * @param additionalPorts
	 *            - a list of property names that will be assigned free ports.
	 *            These properties will be added to this instance's service
	 *            registration as well as being used in the processing of the
	 *            configurations.
	 */
	public AutoConfigure(String serviceFormat, String hostVariable,
			String portVariable, String networkInterface, int addressIndex,
			Map<String, String> serviceProperties, ServiceScope discovery,
			List<Service> serviceDefinitions,
			List<ServiceCollection> serviceCollectionDefinitions,
			List<File> configurations, Map<String, String> substitutions,
			List<UniqueDirectory> uniqueDirectories,
			List<String> additionalPorts) {
		this.serviceFormat = serviceFormat;
		this.hostVariable = hostVariable;
		this.portVariable = portVariable;
		this.networkInterface = networkInterface;
		this.addressIndex = addressIndex;
		this.serviceProperties = serviceProperties;
		this.discovery = discovery;
		this.configurations = configurations;
		this.substitutions = substitutions;
		this.uniqueDirectories = uniqueDirectories;

		for (Service service : serviceDefinitions) {
			services.put(serviceListener(), service);
		}
		for (ServiceCollection collection : serviceCollectionDefinitions) {
			serviceCollections.put(serviceCollectionListener(), collection);
		}
		for (String p : additionalPorts) {
			this.additionalPorts.put(p, p);
		}
	}

	/**
	 * Run the auto configuration process.
	 * 
	 * @param success
	 *            - the closure to evaluate upon successful auto configuration
	 * @param failure
	 *            - the closure to evaluate upon failure to auto configure
	 * @param timeout
	 *            - the length of time to wait for auto configuration to
	 *            complete
	 * @param unit
	 *            - the unit of the wait time
	 */
	public void configure(ConfiguarationAction success,
			ConfiguarationAction failure, long timeout, TimeUnit unit) {
		configure(new HashMap<String, String>(), success, failure, timeout,
				unit);
	}

	/**
	 * Run the auto configuration process.
	 * 
	 * @param success
	 *            - the closure to evaluate upon successful auto configuration
	 * @param failure
	 *            - the closure to evaluate upon failure to auto configure
	 * @param timeout
	 *            - the length of time to wait for auto configuration to
	 *            complete
	 * @param unit
	 *            - the unit of the wait time
	 * @param runProperties
	 *            - a map of substitutions that are added to the mix when
	 *            processing configurations
	 */
	public void configure(Map<String, String> runProperties,
			ConfiguarationAction success, ConfiguarationAction failure,
			long timeout, TimeUnit unit) {
		this.runProperties.putAll(runProperties);
		logger.info(String.format("Using runtime property overrides %s",
				runProperties));
		logger.info("Beginning auto configuration process");
		Runnable successAction = successAction(success, failure);
		int cardinality = getCardinality();
		if (!rendezvous.compareAndSet(null, new Rendezvous(cardinality,
				successAction, failureAction(failure)))) {
			throw new IllegalStateException("System is already configuring!");
		}
		try {
			registerService();
		} catch (Throwable e) {
			logger.log(Level.SEVERE, "Unable to register this service!", e);
			failure.run(transformedConfigurations);
			return;
		}

		if (cardinality == 0) {
			// no services required
			successAction.run();
			return;
		}

		rendezvous
				.get()
				.scheduleCancellation(
						timeout,
						unit,
						Executors
								.newSingleThreadScheduledExecutor(new LabeledThreadFactory(
										"Auto Configuration Scheduling Thread")));
		try {
			registerListeners();
			registerService();
		} catch (Throwable e) {
			logger.log(Level.SEVERE, "Error registering service listeners", e);
			failure.run(transformedConfigurations);
		}
	}

	protected void allocateAdditionalPorts() {
		for (Map.Entry<String, String> entry : additionalPorts.entrySet()) {
			entry.setValue(String.valueOf(Utils.allocatePort(bound.get()
					.getAddress())));
		}
	}

	protected void allocatePort() {
		NetworkInterface iface;
		try {
			iface = NetworkInterface.getByName(networkInterface);
		} catch (SocketException e) {
			String msg = String.format(
					"Unable to obtain network interface[%s]", networkInterface);
			logger.log(Level.SEVERE, msg, e);
			throw new IllegalStateException(msg, e);
		}
		if (iface == null) {
			String msg = String.format("Unable to find network interface [%s]",
					networkInterface);
			logger.severe(msg);
			throw new IllegalStateException(msg);
		}
		try {
			if (!iface.isUp()) {
				String msg = String.format("Network interface [%s] is not up!",
						networkInterface);
				logger.severe(msg);
				throw new IllegalStateException(msg);
			}
		} catch (SocketException e) {
			String msg = String.format(
					"Unable to determin if network interface [%s] is up",
					networkInterface);
			logger.severe(msg);
			throw new IllegalStateException(msg);
		}
		logger.info(String.format("Network interface [%s] is up",
				iface.getDisplayName()));
		Enumeration<InetAddress> interfaceAddresses = iface.getInetAddresses();
		InetAddress raw = null;
		for (int i = 0; i <= addressIndex; i++) {
			if (!interfaceAddresses.hasMoreElements()) {
				String msg = String
						.format("Unable to find any network address for interface[%s] {%s}",
								networkInterface, iface.getDisplayName());
				logger.severe(msg);
				throw new IllegalStateException(msg);
			}
			raw = interfaceAddresses.nextElement();
		}
		if (raw == null) {
			String msg = String
					.format("Unable to find any network address for interface[%s] {%s}",
							networkInterface, iface.getDisplayName());
			logger.severe(msg);
			throw new IllegalStateException(msg);
		}
		InetAddress address;
		try {
			address = InetAddress.getByName(raw.getCanonicalHostName());
		} catch (UnknownHostException e) {
			String msg = String
					.format("Unable to resolve network address [%s] for interface[%s] {%s}",
							raw, networkInterface, iface.getDisplayName());
			logger.log(Level.SEVERE, msg, e);
			throw new IllegalStateException(msg, e);
		}
		int port = Utils.allocatePort(address);
		if (port <= 0) {
			String msg = String.format(
					"Unable to allocate port on address [%s]", address);
			logger.severe(msg);
			throw new IllegalStateException(msg);
		}
		InetSocketAddress boundAddress = new InetSocketAddress(address, port);
		logger.info(String.format("Binding this service to [%s]", boundAddress));
		bound.set(boundAddress);
	}

	protected void discover(ServiceReference reference, Service service) {
		logger.info(String.format("discovered [%s] for service [%s]",
				reference.getUrl(), service));
		if (service.isDiscovered()) {
			logger.warning(String.format(
					"Service [%s] has already been discovered!", service));
			return;
		}
		service.discover(reference);
		try {
			rendezvous.get().meet();
		} catch (BrokenBarrierException e) {
			logger.finest("Barrier already broken");
		}
	}

	protected void discover(ServiceReference reference,
			ServiceCollection serviceCollection) {
		logger.info(String.format(
				"discovered [%s] for service collection [%s]",
				reference.getUrl(), serviceCollection));
		serviceCollection.discover(reference);
		try {
			rendezvous.get().meet();
		} catch (BrokenBarrierException e) {
			logger.finest("Barrier already broken");
		}
	}

	protected Runnable failureAction(final ConfiguarationAction failure) {
		return new Runnable() {
			@Override
			public void run() {
				logger.severe("Auto configuration failed due to not all services being discovered");
				for (Service service : services.values()) {
					if (!service.isDiscovered()) {
						logger.severe(String
								.format("Service [%s] has not been discovered",
										service));
					}
				}
				for (ServiceCollection serviceCollection : serviceCollections
						.values()) {
					if (!serviceCollection.isSatisfied()) {
						int cardinality = serviceCollection.cardinality;
						int discoveredCardinality = serviceCollection
								.getDiscoveredCardinality();
						logger.severe(String
								.format("Service collection [%s] has not been satisfied, missing %s services",
										serviceCollection, cardinality
												- discoveredCardinality,
										cardinality));
					}
				}
				failure.run(transformedConfigurations);
			}
		};
	}

	protected Map<String, String> gatherPropertySubstitutions() {
		Map<String, String> properties = new HashMap<>();

		// Add any substitutions supplied
		properties.putAll(substitutions);

		// Add the bound host:port of this service
		properties.put(hostVariable, bound.get().getHostName());
		properties.put(portVariable, Integer.toString(bound.get().getPort()));

		// Add the additional ports for this service
		for (Map.Entry<String, String> entry : additionalPorts.entrySet()) {
			properties.put(String.format("my.%s", entry.getKey()),
					entry.getValue());
		}

		// Add all the substitutions for the service collections
		for (ServiceCollection serviceCollection : serviceCollections.values()) {
			properties.put(serviceCollection.variable,
					serviceCollection.resolve());
		}

		// Add all the substitutions for the services
		for (Service service : services.values()) {
			properties.put(service.variable, service.resolve());
		}

		// Resolve all unique directories
		for (UniqueDirectory uDir : uniqueDirectories) {
			try {
				properties.put(uDir.variable, uDir.resolve().getAbsolutePath());
			} catch (IOException e) {
				String msg = String.format(
						"Cannot create unique directory [%s]", uDir);
				logger.log(Level.SEVERE, msg, e);
				throw new IllegalStateException(msg, e);
			}
		}

		// Let the system properties override any configuration, if present
		for (Map.Entry<Object, Object> entry : System.getProperties()
				.entrySet()) {
			properties.put(String.valueOf(entry.getKey()),
					String.valueOf(entry.getValue()));
		}

		// Finally, add any property overrides that were specified during the
		// runtime call to configure.
		properties.putAll(runProperties);

		logger.info(String
				.format("Using property substitions [%s]", properties));
		return properties;
	}

	protected InetSocketAddress getBound() {
		return bound.get();
	}

	protected int getCardinality() {
		int cardinality = 0;
		cardinality += services.size();
		for (ServiceCollection collection : serviceCollections.values()) {
			cardinality += collection.cardinality;
		}
		logger.info(String.format("Expecting %s service registrations",
				cardinality));
		return cardinality;
	}

	/**
	 * Process the configuration file's content by creating a new version of it,
	 * substituting the variables necessary.
	 * 
	 * @param configFile
	 * @param propertySubstitutions
	 *            - the map of variables to replace in the configuration
	 * @return the File containing the processed content
	 */
	protected File process(File configFile,
			Map<String, String> propertySubstitutions) {
		File destination;
		try {
			destination = File.createTempFile(
					String.format("%s-", configFile.getName()), ".processed",
					configFile.getParentFile());
			destination.deleteOnExit();
		} catch (IOException e) {
			String msg = String
					.format("Cannot create transformed file for processing the configuration file [%s]",
							configFile.getAbsolutePath());
			logger.log(Level.SEVERE, msg, e);
			throw new IllegalStateException(msg, e);
		}
		try (InputStream is = new FileInputStream(configFile);
				OutputStream os = new FileOutputStream(destination);) {
			Utils.replaceProperties(is, os, propertySubstitutions);
			logger.info(String.format(
					"processed configuration file [%s] > [%s]",
					configFile.getAbsolutePath(), destination.getAbsolutePath()));
		} catch (FileNotFoundException e) {
			String msg = String
					.format("Cannot open file during configuration processing  [%s] > [%s]",
							destination.getAbsolutePath(),
							configFile.getAbsolutePath());
			logger.log(Level.SEVERE, msg, e);
			throw new IllegalStateException(msg, e);
		} catch (IOException e) {
			String msg = String
					.format("Error processing the configuration file [%s] > [%s]",
							configFile.getAbsolutePath(),
							destination.getAbsolutePath());
			logger.log(Level.SEVERE, msg, e);
			throw new IllegalStateException(msg, e);
		}

		return destination;
	}

	protected void processConfigurations(Map<String, String> propertySubstitions) {
		for (File configFile : configurations) {
			if (!configFile.exists()) {
				String msg = String.format("missing configuration file [%s]",
						configFile.getAbsolutePath());
				logger.severe(msg);
				throw new IllegalStateException(msg);
			}
			transformedConfigurations.add(process(configFile,
					propertySubstitions));
		}
	}

	protected void registerListeners() {
		registerServiceCollectionListeners();
		registerServiceListeners();
	}

	protected void registerService() {
		allocatePort();
		allocateAdditionalPorts();
		String service = String.format(serviceFormat,
				bound.get().getHostName(), bound.get().getPort());
		Map<String, String> properties = new HashMap<>();
		properties.putAll(serviceProperties);
		properties.putAll(additionalPorts);
		try {
			ServiceURL url = new ServiceURL(service);
			logger.info(String.format(
					"Registering this service as [%s] with properties %s", url,
					properties));
			serviceRegistration.set(discovery.register(url, properties));
		} catch (MalformedURLException e) {
			String msg = String.format("Invalid syntax for service URL [%s]",
					service);
			logger.log(Level.SEVERE, msg, e);
			throw new IllegalArgumentException(msg, e);
		}
	}

	protected void registerServiceCollectionListeners() {
		for (Map.Entry<ServiceListener, ServiceCollection> entry : serviceCollections
				.entrySet()) {
			ServiceCollection service = entry.getValue();
			try {
				logger.info(String.format(
						"Registering listener for service collection %s",
						service));
				discovery.addServiceListener(entry.getKey(),
						service.constructFilter());
			} catch (InvalidSyntaxException e) {
				String msg = String
						.format("Invalid syntax for discovered service collection [%s]",
								service);
				logger.log(Level.SEVERE, msg, e);
				throw new IllegalArgumentException(msg, e);
			}
		}
	}

	protected void registerServiceListeners() {
		for (Map.Entry<ServiceListener, Service> entry : services.entrySet()) {
			Service service = entry.getValue();
			try {
				logger.info(String.format(
						"Registering listener for service [%s]", service));
				discovery.addServiceListener(entry.getKey(),
						service.constructFilter());
			} catch (InvalidSyntaxException e) {
				String msg = String.format(
						"Invalid syntax for discovered service [%s]", service);
				logger.log(Level.SEVERE, msg, e);
				throw new IllegalArgumentException(msg, e);
			}
		}
	}

	protected ServiceListener serviceCollectionListener() {
		return new ServiceListener() {
			@Override
			public void serviceChanged(ServiceEvent event) {
				ServiceReference reference = event.getReference();
				switch (event.getType()) {
				case REGISTERED:
					ServiceCollection serviceCollection = serviceCollections
							.get(this);
					if (serviceCollection == null) {
						String msg = String.format(
								"No existing listener matching [%s]",
								reference.getUrl());
						logger.severe(msg);
						throw new IllegalStateException(msg);
					}
					discover(reference, serviceCollection);
					break;
				case UNREGISTERED:
					String msg = String
							.format("service [%s] has been unregistered after acquisition",
									reference.getUrl());
					logger.info(msg);
					break;
				case MODIFIED:
					logger.info(String.format(
							"service [%s] has been modified after acquisition",
							reference.getUrl()));
					break;
				}
			}
		};
	}

	protected ServiceListener serviceListener() {
		return new ServiceListener() {
			@Override
			public void serviceChanged(ServiceEvent event) {
				ServiceReference reference = event.getReference();
				if (reference.getRegistration().equals(
						serviceRegistration.get())) {
					logger.finest(String
							.format("Ignoring service event for this instance's service"));
					return;
				}
				switch (event.getType()) {
				case REGISTERED:
					Service service = services.get(this);
					if (service == null) {
						String msg = String.format(
								"No existing listener matching [%s]",
								reference.getUrl());
						logger.severe(msg);
						throw new IllegalStateException(msg);
					}
					discover(reference, service);
					break;
				case UNREGISTERED:
					logger.info(String
							.format("service [%s] has been unregistered after acquisition",
									reference.getUrl()));
					break;
				case MODIFIED:
					logger.info(String.format(
							"service [%s] has been modified after acquisition",
							reference.getUrl()));
					break;
				}
			}
		};
	}

	protected Runnable successAction(final ConfiguarationAction success,
			final ConfiguarationAction failure) {
		return new Runnable() {
			@Override
			public void run() {
				logger.info("All services have been discovered");
				try {
					processConfigurations(gatherPropertySubstitutions());
				} catch (Throwable e) {
					logger.log(Level.SEVERE, "Error processing configurations",
							e);
					failure.run(transformedConfigurations);
					return;
				}
				logger.info("Auto configuration successfully completed, running success action");
				try {
					success.run(transformedConfigurations);
					logger.info("Success action completed");
				} catch (Throwable e) {
					logger.log(
							Level.SEVERE,
							"Exception encountered during the running success action",
							e);
					logger.info("Running failure action");
					failure.run(transformedConfigurations);
				}
			}
		};
	}
}
