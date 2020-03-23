/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog.vm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.ligoj.app.plugin.prov.azure.catalog.AbstractAzurePrice;
import org.ligoj.app.plugin.prov.azure.catalog.NamedResource;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Azure compute prices for a fixed term.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ComputePrices extends AbstractAzurePrice<AzureVmOffer> {

	/**
	 * All software licenses.
	 */
	private List<NamedResource> softwareLicenses = new ArrayList<>();
	private Map<String, String> softwareById = new TreeMap<>(Collections.reverseOrder());

	
	/**
	 * All sizes.
	 */
	private List<NamedResource> sizesOneYear = new ArrayList<>();
	private List<NamedResource> sizesThreeYear = new ArrayList<>();
	private List<NamedResource> sizesPayGo = new ArrayList<>();
	
}