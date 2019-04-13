/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Azure VM prices for an instance type.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AzureComputePrice {

	/**
	 * Optional base price.
	 */
	private String baseOfferSlug;

	/**
	 * Number of cores.
	 */
	private int cores;

	private String series;

	/**
	 * Price per regions. Key is the Azure region identifier. Value is the actual price.
	 */
	private Map<String, ValueWrapper> prices;
}
