/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure;

import java.io.IOException;

import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.ligoj.app.plugin.prov.ProvResource;
import org.ligoj.app.plugin.prov.azure.catalog.AzurePriceImport;
import org.ligoj.app.plugin.prov.catalog.ImportCatalogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The provisioning service for Azure. There is complete quote configuration along the subscription.
 */
@Service
@Path(ProvAzurePluginResource.SERVICE_URL)
@Produces(MediaType.APPLICATION_JSON)
public class ProvAzurePluginResource extends AbstractAzureToolPluginResource implements ImportCatalogService {

	/**
	 * Plug-in key.
	 */
	public static final String SERVICE_URL = ProvResource.SERVICE_URL + "/azure";

	/**
	 * Plug-in key.
	 */
	public static final String KEY = SERVICE_URL.replace('/', ':').substring(1);

	@Autowired
	protected AzurePriceImport priceImport;

	@Override
	public String getKey() {
		return KEY;
	}

	/**
	 * Fetch the prices from the Azure server. Install or update the prices
	 */
	@Override
	public void install() throws IOException {
		priceImport.install(false);
	}

	@Override
	public void updateCatalog(final String node, final boolean force) throws IOException {
		// Azure catalog is shared with all instances, require tool level access
		nodeResource.checkWritableNode(KEY);
		priceImport.install(force);
	}

	@Override
	public void create(final int subscription) {
		// Authenticate only for the check
		authenticate(subscriptionResource.getParameters(subscription), new AzureCurlProcessor());
	}
}
