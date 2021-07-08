/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.guacamole;

import org.apache.guacamole.tunnel.TunnelModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Stage;
import com.google.inject.servlet.GuiceServletContextListener;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.servlet.ServletContextEvent;
import org.apache.guacamole.environment.Environment;
import org.apache.guacamole.environment.LocalEnvironment;
import org.apache.guacamole.extension.ExtensionModule;
import org.apache.guacamole.log.LogModule;
import org.apache.guacamole.net.auth.AuthenticationProvider;
import org.apache.guacamole.rest.RESTServiceModule;
import org.apache.guacamole.rest.auth.HashTokenSessionMap;
import org.apache.guacamole.rest.auth.TokenSessionMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A ServletContextListener to listen for initialization of the servlet context
 * in order to set up dependency injection.
 *
 * NOTE: Guacamole's REST API uses Jersey 2.x which does not natively support
 * dependency injection using Guice. It DOES support dependency injection using
 * HK2, which supports bi-directional bridging with Guice.
 *
 * The overall process is thus:
 *
 * 1. Application initialization proceeds using GuacamoleServletContextListener,
 *    a subclass of GuiceServletContextListener, with all HTTP requests being
 *    routed through GuiceFilter which serves as the absolute root.
 *
 * 2. GuacamoleServletContextListener prepares the Guice injector, storing the
 *    injector within the ServletContext such that it can later be bridged with
 *    HK2.
 *
 * 3. Several of the modules used to prepare the Guice injector are
 *    ServletModule subclasses, which define HTTP request paths that GuiceFilter
 *    should route to specific servlets. One of these paths is "/api/*" (the
 *    root of the REST API) which is routed to Jersey's ServletContainer servlet
 *    (the root of Jersey's JAX-RS implementation).
 *
 * 4. Configuration information passed to Jersey's ServletContainer tells Jersey
 *    to use the GuacamoleApplication class (a subclass of ResourceConfig) to
 *    define the rest of the resources and any other configuration.
 *
 * 5. When Jersey creates its instance of GuacamoleApplication, the
 *    initialization process of GuacamoleApplication pulls the Guice injector
 *    from the ServletContext, completes the HK2 bridging, and configures Jersey
 *    to automatically locate and inject all REST services.
 */
public class GuacamoleServletContextListener extends GuiceServletContextListener {

    /**
     * The name of the ServletContext attribute which will contain a reference
     * to the Guice injector once the contextInitialized() event has been
     * handled.
     */
    public static final String GUICE_INJECTOR = "GUAC_GUICE_INJECTOR";

    /**
     * Logger for this class.
     */
    private final Logger logger = LoggerFactory.getLogger(GuacamoleServletContextListener.class);

    /**
     * The Guacamole server environment.
     */
    private Environment environment;

    /**
     * Singleton instance of a TokenSessionMap.
     */
    private TokenSessionMap sessionMap;

    /**
     * List of all authentication providers from all loaded extensions.
     */
    @Inject
    private List<AuthenticationProvider> authProviders;

    /**
     * Internal reference to the Guice injector that was lazily created when
     * getInjector() was first invoked.
     */
    private final AtomicReference<Injector> guiceInjector = new AtomicReference<>();

    @Override
    public void contextInitialized(ServletContextEvent servletContextEvent) {

        try {
            environment = new LocalEnvironment();
            sessionMap = new HashTokenSessionMap(environment);
        }
        catch (GuacamoleException e) {
            logger.error("Unable to read guacamole.properties: {}", e.getMessage());
            logger.debug("Error reading guacamole.properties.", e);
            throw new RuntimeException(e);
        }

        // NOTE: The superclass implementation of contextInitialized() is
        // expected to invoke getInjector(), hence the need to call AFTER
        // setting up the environment and session map
        super.contextInitialized(servletContextEvent);

        // Inject any annotated members of this class
        Injector injector = getInjector();
        injector.injectMembers(this);

        // Store reference to injector for use by Jersey and HK2 bridge
        servletContextEvent.getServletContext().setAttribute(GUICE_INJECTOR, injector);

    }

    @Override
    protected Injector getInjector() {
        return guiceInjector.updateAndGet((current) -> {

            // Use existing injector if already created
            if (current != null)
                return current;

            // Create new injector if necessary
            Injector injector = Guice.createInjector(Stage.PRODUCTION,
                new EnvironmentModule(environment),
                new LogModule(environment),
                new ExtensionModule(environment),
                new RESTServiceModule(sessionMap),
                new TunnelModule()
            );

            return injector;

        });
    }

    @Override
    public void contextDestroyed(ServletContextEvent servletContextEvent) {

        // Clean up reference to Guice injector
        servletContextEvent.getServletContext().removeAttribute(GUICE_INJECTOR);

        // Shutdown TokenSessionMap
        if (sessionMap != null)
            sessionMap.shutdown();

        // Unload all extensions
        if (authProviders != null) {
            for (AuthenticationProvider authProvider : authProviders)
                authProvider.shutdown();
        }

        // Continue any Guice-specific cleanup
        super.contextDestroyed(servletContextEvent);

    }

}
