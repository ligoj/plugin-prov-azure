package org.ligoj.app.plugin.prov.azure;

import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.ligoj.app.plugin.prov.ProvResource;
import org.springframework.stereotype.Service;

/**
 * The provisioning service for Azure. There is complete quote configuration
 * along the subscription.
 */
@Service
@Path(ProvAzurePluginResource.SERVICE_URL)
@Produces(MediaType.APPLICATION_JSON)
public class ProvAzurePluginResource extends AbstractAzureToolPluginResource {

	/**
	 * Plug-in key.
	 */
	public static final String SERVICE_URL = ProvResource.SERVICE_URL + "/azure";

	/**
	 * Plug-in key.
	 */
	public static final String KEY = SERVICE_URL.replace('/', ':').substring(1);

	@Override
	public String getKey() {
		return KEY;
	}
}
