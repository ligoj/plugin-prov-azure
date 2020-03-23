/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog.database;

import java.util.ArrayList;
import java.util.List;

import org.ligoj.app.plugin.prov.azure.catalog.AbstractAzurePrice;
import org.ligoj.app.plugin.prov.azure.catalog.NamedResource;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Azure database prices for a fixed term.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class DatabasePrices extends AbstractAzurePrice<AzureDatabaseOffer> {

	/**
	 * All sizes.
	 */
	private List<NamedResource> computeTypes = new ArrayList<>();
}