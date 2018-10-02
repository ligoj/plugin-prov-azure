/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure;

import java.io.IOException;

import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.ligoj.app.plugin.prov.ProvResource;
import org.ligoj.app.plugin.prov.azure.in.ProvAzurePriceImportResource;
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
	protected ProvAzurePriceImportResource priceImport;

	@Override
	public String getKey() {
		return KEY;
	}

	/**
	 * Fetch the prices from the AWS server. Install or update the prices
	 */
	@Override
	public void install() throws IOException {
		priceImport.install();
	}

	@Override
	public void updateCatalog(final String node) throws IOException {
		// Azure catalog is shared with all instances, require tool level access
		nodeResource.checkWritableNode(KEY);
		priceImport.install();
	}

	@Override
	public void create(final int subscription) {
		// Authenticate only for the check
		authenticate(subscriptionResource.getParameters(subscription), new AzureCurlProcessor());
	}
}
