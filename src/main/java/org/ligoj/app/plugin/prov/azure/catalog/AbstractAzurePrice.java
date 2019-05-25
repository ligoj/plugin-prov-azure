/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

/**
 * Azure prices.
 * @param <P> The price type.
 */
@Getter
@Setter
public abstract class AbstractAzurePrice<P> {

	/**
	 * All available regions.
	 */
	private List<NamedResource> regions = new ArrayList<>();

	/**
	 * All offers, where the key is the combination of tier and size, plus snapshots, plus transactions cost for
	 * standard disks.
	 */
	private Map<String, P> offers = new HashMap<>();

}
