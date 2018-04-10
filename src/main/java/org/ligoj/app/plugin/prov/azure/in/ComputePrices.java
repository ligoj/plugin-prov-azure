/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.in;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Azure compute prices. Each term 
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ComputePrices {

	/**
	 * Prices of instances types Enabled VM. Only VM enabled for the current term are present.
	 */
	private Map<String, AzureVmPrice> offers;
}
