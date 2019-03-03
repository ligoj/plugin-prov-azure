/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog;

import java.io.IOException;
import java.net.URISyntaxException;

import org.ligoj.app.plugin.prov.azure.ProvAzurePluginResource;
import org.ligoj.app.plugin.prov.azure.catalog.disk.AzurePriceImportDisk;
import org.ligoj.app.plugin.prov.azure.catalog.support.AzurePriceImportSupport;
import org.ligoj.app.plugin.prov.azure.catalog.vm.AzurePriceImportVm;
import org.ligoj.app.plugin.prov.catalog.AbstractImportCatalogResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.Setter;

/**
 * The provisioning price service for Azure. Manage install or update of prices.<br>
 */
@Component
@Setter
public class AzurePriceImport extends AbstractImportCatalogResource {

	@Autowired
	private AzurePriceImportBase base;

	@Autowired
	private AzurePriceImportVm vm;

	@Autowired
	private AzurePriceImportDisk disk;

	@Autowired
	private AzurePriceImportSupport support;

	/**
	 * Install or update prices.
	 *
	 * @throws IOException
	 *             When CSV or XML files cannot be read.
	 * @throws URISyntaxException
	 *             When CSV or XML files cannot be read.
	 */
	public void install() throws IOException {
		final UpdateContext context = new UpdateContext();
		context.setNode(nodeRepository.findOneExpected(ProvAzurePluginResource.KEY));

		base.install(context);
		vm.install(context);
		disk.install(context);
		support.install(context);
	}
}