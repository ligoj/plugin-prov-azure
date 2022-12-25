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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Azure compute prices for a fixed term.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ComputePrices extends AbstractAzurePrice<AzureVmOffer> {

	/**
	 * All software licenses.
	 */
	@Getter
	@Setter
	private List<NamedResource> softwareLicenses = new ArrayList<>();

	@Getter
	@JsonIgnore
	private final Map<String, String> softwareById = new TreeMap<>(Collections.reverseOrder());

	/**
	 * All sizes.
	 */
	@Getter
	private final List<NamedResource> sizesOneYear = new ArrayList<>();

	@Getter
	private final List<NamedResource> sizesThreeYear = new ArrayList<>();

	@Getter
	private final List<NamedResource> sizesSavingOneYear = new ArrayList<>();

	@Getter
	private final List<NamedResource> sizesSavingThreeYear = new ArrayList<>();

	@Getter
	private final List<NamedResource> sizesFiveYear = new ArrayList<>();

	@Getter
	private final List<NamedResource> sizesPayGo = new ArrayList<>();

}