package org.ligoj.app.plugin.prov.azure.in;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ManagedDisks {

	/**
	 * All available regions.
	 */
	private List<NamedResource> regions = new ArrayList<>();

	/**
	 * All offers, where the key is the combination of tier and size, plus snapshots, plus transactions cost for
	 * standard disks.
	 */
	private Map<String, ManagedDisk> offers = new HashMap<>();

}
