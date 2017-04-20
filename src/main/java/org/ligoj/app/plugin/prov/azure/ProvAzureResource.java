package org.ligoj.app.plugin.prov.azure;

import java.util.Arrays;
import java.util.List;

import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.ligoj.app.model.Node;
import org.ligoj.app.plugin.prov.ProvResource;
import org.ligoj.app.plugin.prov.model.ProvInstance;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceType;
import org.ligoj.app.plugin.prov.model.ProvStorage;
import org.ligoj.app.resource.plugin.AbstractToolPluginResource;
import org.springframework.stereotype.Service;

/**
 * The provisioning service for Azure. There is complete quote configuration
 * along the subscription.
 */
@Service
@Path(ProvAzureResource.SERVICE_URL)
@Produces(MediaType.APPLICATION_JSON)
public class ProvAzureResource extends AbstractToolPluginResource {

	/**
	 * Plug-in key.
	 */
	public static final String SERVICE_URL = ProvResource.SERVICE_URL + "/azure";

	/**
	 * Plug-in key.
	 */
	public static final String SERVICE_KEY = SERVICE_URL.replace('/', ':').substring(1);

	@Override
	public String getKey() {
		return SERVICE_KEY;
	}

	@Override
	public List<Class<?>> getInstalledEntities() {
		return Arrays.asList(Node.class, ProvInstancePriceType.class, ProvInstance.class, ProvInstancePrice.class,
				ProvStorage.class);
	}

}
