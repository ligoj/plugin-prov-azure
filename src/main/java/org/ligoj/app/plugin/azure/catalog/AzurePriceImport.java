/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.azure.catalog;

import java.io.IOException;

import jakarta.transaction.Transactional;

import org.ligoj.app.plugin.azure.ProvAzurePluginResource;
import org.ligoj.app.plugin.azure.catalog.database.AzurePriceImportDatabase;
import org.ligoj.app.plugin.azure.catalog.disk.AzurePriceImportDisk;
import org.ligoj.app.plugin.azure.catalog.support.AzurePriceImportSupport;
import org.ligoj.app.plugin.azure.catalog.vm.AzurePriceImportVm;
import org.ligoj.app.plugin.prov.catalog.AbstractImportCatalogResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * The provisioning price service for Azure. Manage install or update of prices.<br>
 */
@Component
@Setter
@Slf4j
public class AzurePriceImport extends AbstractImportCatalogResource {

	@Autowired
	private AzurePriceImportBase base;

	@Autowired
	private AzurePriceImportVm vm;

	@Autowired
	private AzurePriceImportDisk disk;

	@Autowired
	private AzurePriceImportDatabase database;

	@Autowired
	private AzurePriceImportSupport support;

	/**
	 * Install or update prices.<br>
	 * The whole update is executed inside a single transaction: prices are accumulated in the persistence context and
	 * flushed by chunks with JDBC batching instead of one transaction per price. On failure, the previous catalog is
	 * fully preserved.
	 *
	 * @param force When <code>true</code>, all cost attributes are update.
	 * @throws IOException When CSV or XML files cannot be read.
	 */
	@Transactional
	public void install(final boolean force) throws IOException {
		final var context = initContext(new UpdateContext(), ProvAzurePluginResource.KEY, force);
		initJdbcBatch();

		base.install(context);
		vm.install(context);
		database.install(context);
		disk.install(context);
		support.install(context);

		if (!context.getReportedMissingReferences().isEmpty()) {
			log.info("{} unresolvable catalog references were skipped, enable DEBUG level on {} for details",
					context.getReportedMissingReferences().size(), AbstractVmAzureImport.class.getName());
		}
	}
}
