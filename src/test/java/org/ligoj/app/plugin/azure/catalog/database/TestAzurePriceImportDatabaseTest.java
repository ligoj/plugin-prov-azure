/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.azure.catalog.database;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test class of {@link AzurePriceImportDatabase}: flexible-server size specifications.
 */
class TestAzurePriceImportDatabaseTest {

	private final AzurePriceImportDatabase importer = new AzurePriceImportDatabase();

	@Test
	void toRamGiBFromOffer() {
		// The offer RAM is authoritative when provided (PostgreSQL)
		final var offer = new AzureDatabaseOffer();
		offer.setMemory(48);
		Assertions.assertEquals(48d, importer.toRamGiB(offer, 'b', 12, "ms"));
	}

	@Test
	void toRamGiBDerived() {
		// General purpose (d): 4 GiB/vCore, memory optimized (e): 8 GiB/vCore
		Assertions.assertEquals(64d, importer.toRamGiB(null, 'd', 16, "dsv5"));
		Assertions.assertEquals(384d, importer.toRamGiB(null, 'e', 48, "dsv5"));

		// Burstable: 'ms' 4 GiB/vCore, 's' 2 GiB/vCore, halved for single vCore
		Assertions.assertEquals(1d, importer.toRamGiB(null, 'b', 1, "s"));
		Assertions.assertEquals(2d, importer.toRamGiB(null, 'b', 1, "ms"));
		Assertions.assertEquals(4d, importer.toRamGiB(null, 'b', 2, "s"));
		Assertions.assertEquals(8d, importer.toRamGiB(null, 'b', 2, "ms"));
		Assertions.assertEquals(48d, importer.toRamGiB(null, 'b', 12, "ms"));

		// MySQL 'b2ms2' variant: suffix still contains 'ms'
		Assertions.assertEquals(8d, importer.toRamGiB(null, 'b', 2, "ms2"));
	}
}
