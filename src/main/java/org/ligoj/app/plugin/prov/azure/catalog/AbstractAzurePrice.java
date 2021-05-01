/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

/**
 * Azure prices.
 *
 * @param <P> The price type.
 */
public abstract class AbstractAzurePrice<P> {

	/**
	 * All available regions.
	 */
	@Getter
	@Setter
	private List<NamedResource> regions = new ArrayList<>();

	/**
	 * All offers, where the key is the combination of tier and size, plus snapshots, plus transactions cost for
	 * standard disks.
	 */
	@Getter
	@Setter
	private Map<String, P> offers = new HashMap<>();

	/**
	 * All SKUs.
	 */
	@Getter
	@Setter
	private Map<String, Map<String, List<String>>> skus = new HashMap<>();

	/**
	 * All tiers.
	 */
	@Getter
	@Setter
	private List<NamedResource> tiers = new ArrayList<>();

	@Getter
	@JsonIgnore
	private Map<String, String> tiersById = new HashMap<>();

	/**
	 * All billing options.
	 */
	@Getter
	@Setter
	private List<NamedResource> billingOptions = new ArrayList<>();

	@Getter
	@JsonIgnore
	private Map<String, String> billingById = new HashMap<>();

}
