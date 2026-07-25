/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.azure.catalog;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.ligoj.app.plugin.azure.catalog.vm.AzurePriceImportVm;
import org.ligoj.app.plugin.azure.catalog.vm.AzureVmOffer;
import org.ligoj.app.plugin.azure.catalog.vm.ComputePrices;
import org.ligoj.app.plugin.prov.model.ProvInstanceType;

/**
 * Test class of {@link AbstractVmAzureImport}: unresolvable catalog references handling.
 */
class TestAbstractVmAzureImportTest {

	private final AzurePriceImportVm importer = new AzurePriceImportVm();

	private void install(final UpdateContext context, final ComputePrices prices, final List<String> components,
			final String sku, final String termName) {
		importer.installSkuComponents(context, prices, components, sku, termName, (c, t) -> true,
				(type, edition, engine, cost, region) -> Assertions.fail("No price expected"));
	}

	@Test
	void installSkuComponentsMissingOffer() {
		final var context = new UpdateContext();
		final var prices = new ComputePrices();

		// First occurrence is reported
		install(context, prices, List.of("windows-d16nsv6-standard--perhour"), "windows-d16nsv6-standard", "payg");
		Assertions.assertEquals(Set.of("offer:windows-d16nsv6-standard"), context.getReportedMissingReferences());

		// Next occurrences of the same missing offer are not reported again
		install(context, prices, List.of("windows-d16nsv6-standard--perhourspot"), "windows-d16nsv6-standard", "spot");
		Assertions.assertEquals(Set.of("offer:windows-d16nsv6-standard"), context.getReportedMissingReferences());

		// Another missing offer is reported
		install(context, prices, List.of("linux-d16nsv6-standard--perhour"), "windows-d16nsv6-standard", "ahb");
		Assertions.assertEquals(Set.of("offer:windows-d16nsv6-standard", "offer:linux-d16nsv6-standard"),
				context.getReportedMissingReferences());
	}

	@Test
	void installSkuComponentsMissingTiers() {
		final var context = new UpdateContext();
		final var prices = new ComputePrices();
		final var offer = new AzureVmOffer();
		offer.setPrices(Map.of("perhourspot", Map.of()));
		prices.getOffers().put("windows-d192dsv6-standard", offer);

		// Offer exists but only with a spot tiers: the 'payg' reference is reported once
		install(context, prices, List.of("windows-d192dsv6-standard--perhour"), "windows-d192dsv6-standard", "payg");
		install(context, prices, List.of("windows-d192dsv6-standard--perhour"), "windows-d192dsv6-standard", "one-year");
		Assertions.assertEquals(Set.of("tiers:windows-d192dsv6-standard--perhour"),
				context.getReportedMissingReferences());
	}

	@Test
	void isTypelessStandaloneSoftware() {
		// Standalone software rental: the single component references an unpublished offer
		final var prices = new ComputePrices();
		Assertions.assertTrue(importer.isTypeless(prices, Map.of("payg", List.of("autodesk-3ds-max--perhour"))));
	}

	@Test
	void isTypelessNonComputeOffer() {
		// The offer exists but is not a typed compute offer (software, storage, or compute without series)
		final var prices = new ComputePrices();
		prices.getOffers().put("ubuntu-advantage-advanced-prepay", new AzureVmOffer());
		Assertions.assertTrue(
				importer.isTypeless(prices, Map.of("payg", List.of("ubuntu-advantage-advanced-prepay--permonth"))));
	}

	@Test
	void isTypelessTypedOffer() {
		// At least one component resolves a typed compute offer: the SKU is kept
		final var prices = new ComputePrices();
		final var software = new AzureVmOffer();
		final var compute = new AzureVmOffer();
		compute.setType(new ProvInstanceType());
		prices.getOffers().put("windows-ri-16-core", software);
		prices.getOffers().put("linux-d16sv6-standard", compute);
		Assertions.assertFalse(importer.isTypeless(prices, Map.of(
				"payg", List.of("windows-d16sv6-standard--perhour"),
				"one-year", List.of("linux-d16sv6-standard--perhouroneyearreserved", "windows-ri-16-core--perhour"))));
	}
}
