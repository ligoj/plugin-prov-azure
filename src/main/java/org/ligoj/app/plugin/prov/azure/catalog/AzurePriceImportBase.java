/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog;

import java.io.IOException;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.ligoj.app.plugin.prov.azure.ProvAzurePluginResource;
import org.ligoj.bootstrap.core.INamableBean;
import org.springframework.stereotype.Component;

/**
 * The provisioning price service for Azure. Manage install or update of prices.<br>
 */
@Component
public class AzurePriceImportBase extends AbstractAzureImport {

	/**
	 * Configuration key used for enabled regions pattern names. When value is <code>null</code>, no restriction.
	 */
	protected static final String CONF_REGIONS = ProvAzurePluginResource.KEY + ":regions";

	@Override
	public void install(final UpdateContext context) throws IOException {
		nextStep(context, "region");
		context.setValidRegion(Pattern.compile(configuration.get(CONF_REGIONS, ".*")));
		context.getMapRegionById().putAll(toMap("azure-regions.json", MAP_LOCATION));

		// The previously installed location cache. Key is the location Azure name
		context.setRegions(locationRepository.findAllBy(BY_NODE, context.getNode()).stream()
				.filter(r -> isEnabledRegion(context, r))
				.collect(Collectors.toMap(INamableBean::getName, Function.identity())));
	}

}
