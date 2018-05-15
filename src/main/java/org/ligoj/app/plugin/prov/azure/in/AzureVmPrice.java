/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.in;

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
public class AzureVmPrice {

	private int cores;

	/**
	 * RAM, in GiB.
	 */
	private double ram;

	/**
	 * Optional disk size, in GiB
	 */
	private int diskSize;
	private String series;

	/**
	 * Price per regions. Key is the Azure region identifier. Value is the actual price.
	 */
	private Map<String, ValueWrapper> prices;
}
