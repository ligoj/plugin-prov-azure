/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.ligoj.app.plugin.prov.quote.instance.QuoteInstanceQuery.builder;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;

import javax.annotation.PostConstruct;
import javax.transaction.Transactional;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ligoj.app.AbstractServerTest;
import org.ligoj.app.iam.model.CacheCompany;
import org.ligoj.app.iam.model.CacheUser;
import org.ligoj.app.model.DelegateNode;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Parameter;
import org.ligoj.app.model.ParameterValue;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.prov.ProvResource;
import org.ligoj.app.plugin.prov.QuoteVo;
import org.ligoj.app.plugin.prov.azure.ProvAzurePluginResource;
import org.ligoj.app.plugin.prov.azure.catalog.database.AzurePriceImportDatabase;
import org.ligoj.app.plugin.prov.azure.catalog.disk.AzurePriceImportDisk;
import org.ligoj.app.plugin.prov.azure.catalog.support.AzurePriceImportSupport;
import org.ligoj.app.plugin.prov.azure.catalog.vm.AzurePriceImportVm;
import org.ligoj.app.plugin.prov.catalog.AbstractImportCatalogResource;
import org.ligoj.app.plugin.prov.catalog.ImportCatalogResource;
import org.ligoj.app.plugin.prov.dao.ProvDatabasePriceRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstanceTypeRepository;
import org.ligoj.app.plugin.prov.dao.ProvQuoteInstanceRepository;
import org.ligoj.app.plugin.prov.dao.ProvQuoteRepository;
import org.ligoj.app.plugin.prov.dao.ProvQuoteStorageRepository;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvQuote;
import org.ligoj.app.plugin.prov.model.ProvQuoteInstance;
import org.ligoj.app.plugin.prov.model.ProvQuoteStorage;
import org.ligoj.app.plugin.prov.model.ProvStorageOptimized;
import org.ligoj.app.plugin.prov.model.ProvTenancy;
import org.ligoj.app.plugin.prov.model.ProvUsage;
import org.ligoj.app.plugin.prov.model.Rate;
import org.ligoj.app.plugin.prov.model.SupportType;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.ligoj.app.plugin.prov.quote.database.ProvQuoteDatabaseResource;
import org.ligoj.app.plugin.prov.quote.database.QuoteDatabaseQuery;
import org.ligoj.app.plugin.prov.quote.instance.ProvQuoteInstanceResource;
import org.ligoj.app.plugin.prov.quote.instance.QuoteInstanceEditionVo;
import org.ligoj.app.plugin.prov.quote.storage.ProvQuoteStorageResource;
import org.ligoj.app.plugin.prov.quote.storage.QuoteStorageEditionVo;
import org.ligoj.app.plugin.prov.quote.storage.QuoteStorageQuery;
import org.ligoj.app.plugin.prov.quote.support.ProvQuoteSupportResource;
import org.ligoj.bootstrap.resource.system.configuration.ConfigurationResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Test class of {@link AzurePriceImport}
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
class ProvAzurePriceImportTest extends AbstractServerTest {

	private static final double DELTA = 0.001;

	private AzurePriceImport resource;

	@Autowired
	private ProvResource provResource;

	@Autowired
	private ProvQuoteInstanceResource qiResource;

	@Autowired
	private ProvQuoteDatabaseResource qbResource;

	@Autowired
	private ProvQuoteStorageResource qsResource;

	@Autowired
	private ProvQuoteSupportResource qs2Resource;

	@Autowired
	private ProvInstancePriceRepository ipRepository;

	@Autowired
	private ProvDatabasePriceRepository bpRepository;

	@Autowired
	private ProvInstanceTypeRepository itRepository;

	@Autowired
	private ProvQuoteRepository repository;

	@Autowired
	private ConfigurationResource configuration;

	@Autowired
	private ProvQuoteInstanceRepository qiRepository;

	@Autowired
	private ProvQuoteStorageRepository qsRepository;

	protected int subscription;

	@BeforeEach
	void prepareData() throws IOException {
		persistSystemEntities();
		persistEntities("csv",
				new Class[] { Node.class, Project.class, CacheCompany.class, CacheUser.class, DelegateNode.class,
						Parameter.class, ProvLocation.class, Subscription.class, ParameterValue.class,
						ProvQuote.class },
				StandardCharsets.UTF_8.name());
		this.subscription = getSubscription("gStack");

		// Mock catalog import helper
		final var helper = new ImportCatalogResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(helper);
		this.resource = initCatalog(helper, new AzurePriceImport());
		this.resource.setBase(initCatalog(helper, new AzurePriceImportBase()));
		this.resource.setDatabase(initCatalog(helper, new AzurePriceImportDatabase()));
		this.resource.setVm(initCatalog(helper, new AzurePriceImportVm()));
		this.resource.setDisk(initCatalog(helper, new AzurePriceImportDisk()));
		this.resource.setSupport(initCatalog(helper, new AzurePriceImportSupport()));

		clearAllCache();
		configuration.delete(AzurePriceImportBase.CONF_REGIONS);
		initSpringSecurityContext(DEFAULT_USER);
		resetImportTask();

		final var usage12 = new ProvUsage();
		usage12.setName("12month");
		usage12.setRate(100);
		usage12.setDuration(12);
		usage12.setConfiguration(repository.findBy("subscription.id", subscription));
		em.persist(usage12);

		final var usage36 = new ProvUsage();
		usage36.setName("36month");
		usage36.setRate(100);
		usage36.setDuration(36);
		usage36.setConfiguration(repository.findBy("subscription.id", subscription));
		em.persist(usage36);

		final var usageDev = new ProvUsage();
		usageDev.setName("dev");
		usageDev.setRate(30);
		usageDev.setDuration(1);
		usageDev.setConfiguration(repository.findBy("subscription.id", subscription));
		em.persist(usageDev);
		em.flush();
		em.clear();
	}

	private <T extends AbstractImportCatalogResource> T initCatalog(ImportCatalogResource importHelper, T catalog) {
		applicationContext.getAutowireCapableBeanFactory().autowireBean(catalog);
		catalog.setImportCatalogResource(importHelper);
		MethodUtils.getMethodsListWithAnnotation(catalog.getClass(), PostConstruct.class).forEach(m -> {
			try {
				m.invoke(catalog);
			} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
				// Ignore;
			}
		});
		return catalog;
	}

	private void resetImportTask() {
		this.resource.getImportCatalogResource().endTask("service:prov:azure", false);
		this.resource.getImportCatalogResource().startTask("service:prov:azure", t -> {
			t.setLocation(null);
			t.setNbInstancePrices(null);
			t.setNbInstanceTypes(null);
			t.setNbStorageTypes(null);
			t.setWorkload(0);
			t.setDone(0);
			t.setPhase(null);
		});
	}

	@Test
	void installOffLine() throws Exception {
		// Install a new configuration
		final var quote = install();

		// Check the whole quote
		final var instance = check(quote, 431.574d, 150.278d);

		// Check the 3 years term
		var lookup = qiResource.lookup(instance.getConfiguration().getSubscription().getId(),
				builder().cpu(7).ram(1741).autoScale(true).constant(true).usage("36month").build());
		Assertions.assertEquals(150.278d, lookup.getCost(), DELTA);
		Assertions.assertEquals(150.278d, lookup.getPrice().getCost(), DELTA);
		Assertions.assertEquals(5410.008, lookup.getPrice().getCostPeriod(), DELTA);
		Assertions.assertEquals("three-year", lookup.getPrice().getTerm().getCode());
		Assertions.assertFalse(lookup.getPrice().getTerm().isEphemeral());
		Assertions.assertEquals(36.0, lookup.getPrice().getPeriod(), DELTA);
		Assertions.assertEquals("ds4v2", lookup.getPrice().getType().getCode());
		Assertions.assertEquals(8, ipRepository.countBy("term.code", "three-year"));
		Assertions.assertEquals("europe-north", lookup.getPrice().getLocation().getName());
		Assertions.assertEquals("North Europe", lookup.getPrice().getLocation().getDescription());
		checkImportStatus();

		// Install again to check the update without change
		resetImportTask();
		resource.install(false);
		provResource.updateCost(subscription);
		check(provResource.getConfiguration(subscription), 431.574d, 150.278d);
		checkImportStatus();

		// Check price tiers
		lookup = qiResource.lookup(subscription,
				builder().ram(1741).constant(true).os(VmOs.LINUX).usage("dev").build());
		Assertions.assertEquals("europe-north/payg/linux-a1-basic", lookup.getPrice().getCode());

		// Check price including global, hourly and monthly cost
		// +0.025*730 (per hour)
		// +0.15 (global per month)
		// +0.0001*1*730 (regional per core.hour)
		// = 18.25+0.15+0.073
		// = 18.473
		Assertions.assertEquals(18.473d, lookup.getPrice().getCost());
		Assertions.assertEquals(5.542d, lookup.getCost());

		// Point to another catalog with different prices
		configuration.put(AzurePriceImportVm.CONF_API_PRICES, "http://localhost:" + MOCK_PORT + "/v2");

		// Install the new catalog, update occurs
		resetImportTask();
		resource.install(false);
		provResource.updateCost(subscription);

		// Check the new price
		final var newQuote = provResource.getConfiguration(subscription);
		Assertions.assertEquals(439.624d, newQuote.getCost().getMin(), DELTA);

		// Storage price is updated
		final var storage = newQuote.getStorages().get(0);
		Assertions.assertEquals(1.546d, storage.getCost(), DELTA);

		// Compute price is updated
		final var instance2 = newQuote.getInstances().get(0);
		Assertions.assertEquals(151.008d, instance2.getCost(), DELTA);
		var price = instance2.getPrice();
		Assertions.assertEquals(VmOs.LINUX, price.getOs());
		Assertions.assertEquals(ProvTenancy.SHARED, price.getTenancy());
		Assertions.assertEquals(151.008d, price.getCost(), DELTA);
		final var priceType = price.getTerm();
		Assertions.assertEquals("three-year", priceType.getCode());
		Assertions.assertFalse(priceType.isEphemeral());
		Assertions.assertEquals(36, priceType.getPeriod());

		var type = price.getType();
		Assertions.assertEquals("ds4v2", type.getCode());

		// Check rating of "ds4v2"
		Assertions.assertEquals(Rate.GOOD, type.getRamRate());
		Assertions.assertEquals(Rate.GOOD, type.getCpuRate());
		Assertions.assertEquals(Rate.MEDIUM, type.getNetworkRate());
		Assertions.assertEquals(Rate.GOOD, type.getStorageRate());

		// Check rating of "f1"
		type = itRepository.findByCode(subscription, "f1");
		Assertions.assertEquals(Rate.GOOD, type.getRamRate());
		Assertions.assertEquals(Rate.MEDIUM, type.getCpuRate());
		Assertions.assertEquals(Rate.MEDIUM, type.getNetworkRate());
		Assertions.assertEquals(Rate.GOOD, type.getStorageRate());
		Assertions.assertEquals("{\"series\":\"F\",\"disk\":16}", type.getDescription());

		// Check rating of "ds15v2" (dedicated)
		price = ipRepository.findBy("type.code", "ds15v2");
		Assertions.assertEquals(ProvTenancy.DEDICATED, price.getTenancy());

		// Check status
		checkImportStatus();

		// Check some prices
		final var price2 = ipRepository.findBy("code", "europe-north/lowpriority/windows-a1-lowpriority");
		final var term = price2.getTerm();
		Assertions.assertEquals("lowpriority", term.getName());
		Assertions.assertEquals(0, term.getPeriod());
		Assertions.assertEquals("europe-north", price2.getLocation().getName());
		Assertions.assertEquals(VmOs.WINDOWS, price2.getOs());
		Assertions.assertTrue(term.isEphemeral());
		Assertions.assertTrue(price2.getType().isAutoScale());

		// Lookup software
		lookup = qiResource.lookup(subscription,
				builder().ram(1741).os(VmOs.WINDOWS).software("SQL Enterprise").build());
		Assertions.assertEquals("europe-north/payg/sql-enterprise-ds4v2-standard", lookup.getPrice().getCode());
		Assertions.assertEquals("SQL ENTERPRISE", lookup.getPrice().getSoftware());
		Assertions.assertNull(lookup.getPrice().getLicense());

		// Lookup SPOT license
		lookup = qiResource.lookup(subscription, builder().ram(1741).ephemeral(true).os(VmOs.WINDOWS)
				.software("SQL Enterprise").usage("12month").build());
		Assertions.assertEquals("europe-north/spot/sql-enterprise-ds4v2-standard", lookup.getPrice().getCode());

		// Lookup burst
		lookup = qiResource.lookup(subscription, builder().ram(1741).constant(false).build());
		Assertions.assertEquals("europe-north/payg/linux-b2s-standard", lookup.getPrice().getCode());
		Assertions.assertFalse(lookup.getPrice().getType().getConstant().booleanValue());

		// Lookup BYOL license
		lookup = qiResource.lookup(subscription, builder().ram(1741).os(VmOs.WINDOWS).license("BYOL")
				.software("SQL Enterprise").usage("36month").build());
		Assertions.assertEquals("europe-north/byol/three-year/sql-enterprise-ds4v2-standard",
				lookup.getPrice().getCode());
		Assertions.assertEquals("BYOL", lookup.getPrice().getLicense());

		// Check the support
		final var lookupSu = qs2Resource
				.lookup(subscription, 1, SupportType.ALL, SupportType.ALL, SupportType.ALL, SupportType.ALL, Rate.BEST)
				.get(0);
		Assertions.assertEquals("Premier", lookupSu.getPrice().getType().getName());

		// Check the database
		var lookupB = qbResource.lookup(subscription, QuoteDatabaseQuery.builder().cpu(1).engine("MYSQL").build());
		Assertions.assertNull(lookupB.getPrice().getEdition());
		Assertions.assertEquals("europe-north/payg/basic-compute-g5-1/MYSQL", lookupB.getPrice().getCode());
		Assertions.assertEquals(2048, lookupB.getPrice().getType().getRam());
		Assertions.assertEquals(1, lookupB.getPrice().getType().getCpu());
		Assertions.assertNull(lookupB.getPrice().getStorageEngine());
		bpRepository.findAll();

		// SQL Server
		lookupB = qbResource.lookup(subscription, QuoteDatabaseQuery.builder().cpu(4).engine("SQL SERVER").build());
		Assertions.assertEquals("SQL SERVER", lookupB.getPrice().getEngine());
		Assertions.assertEquals("ENTERPRISE", lookupB.getPrice().getEdition());
		Assertions.assertEquals("sql-gp-gen5-4", lookupB.getPrice().getType().getCode());
		Assertions.assertEquals("Gen 5-4 General Purpose", lookupB.getPrice().getType().getName());
		Assertions.assertEquals("europe-north/payg/managed-vcore-general-purpose-gen5-4/SQL SERVER",
				lookupB.getPrice().getCode());
		Assertions.assertEquals(745.266, lookupB.getCost(), DELTA);
		Assertions.assertEquals(5222, lookupB.getPrice().getType().getRam());
		Assertions.assertEquals(4, lookupB.getPrice().getType().getCpu());
		Assertions.assertEquals("SQL SERVER", lookupB.getPrice().getStorageEngine());

		// SQL Server Business Critical
		lookupB = qbResource.lookup(subscription,
				QuoteDatabaseQuery.builder().cpu(4).storageRate(Rate.BEST).engine("SQL SERVER").build());
		Assertions.assertEquals("SQL SERVER", lookupB.getPrice().getEngine());
		Assertions.assertEquals("ENTERPRISE", lookupB.getPrice().getEdition());
		Assertions.assertEquals("sql-bc-gen5-4", lookupB.getPrice().getType().getCode());
		Assertions.assertEquals("Gen 5-4 Business Critical", lookupB.getPrice().getType().getName());
		Assertions.assertEquals("europe-north/payg/managed-vcore-business-critical-gen5-4/SQL SERVER",
				lookupB.getPrice().getCode());
		Assertions.assertEquals(2001.73, lookupB.getCost(), DELTA);
		Assertions.assertEquals(5222, lookupB.getPrice().getType().getRam());
		Assertions.assertEquals(4, lookupB.getPrice().getType().getCpu());
		Assertions.assertEquals("SQL SERVER", lookupB.getPrice().getStorageEngine());
	}

	private void checkImportStatus() {
		final var status = this.resource.getImportCatalogResource().getTask("service:prov:azure");
		Assertions.assertEquals(25, status.getDone());
		Assertions.assertEquals(44, status.getWorkload());
		Assertions.assertEquals("support", status.getPhase());
		Assertions.assertEquals(DEFAULT_USER, status.getAuthor());
		Assertions.assertTrue(status.getNbInstancePrices().intValue() >= 46);
		Assertions.assertEquals(26, status.getNbInstanceTypes().intValue());
		Assertions.assertTrue(status.getNbLocations() >= 1);
		Assertions.assertEquals(28, status.getNbStorageTypes().intValue());
	}

	private void mockServer() throws IOException {
		patchConfigurationUrl();
		mockResource("/managed-disks/calculator/", "managed-disk");
		mockResource("/virtual-machines/calculator/", "virtual-machines");

		// Database part
		mockResource("/mysql/calculator/", "mysql");
		mockResource("/postgresql/calculator/", "postgresql");
		mockResource("/sql-database/calculator/", "sql-database");

		// Another catalog version
		mockResource("/v2/managed-disks/calculator/", "v2/managed-disk");
		mockResource("/v2/virtual-machines/calculator/", "v2/virtual-machines");
		mockResource("/v2/mysql/calculator/", "v2/mysql");
		mockResource("/v2/postgresql/calculator/", "v2/postgresql");
		mockResource("/v2/sql-database/calculator/", "v2/sql-database");
		httpServer.start();
	}

	private void mockResource(final String path, final String json) throws IOException {
		httpServer.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils
				.toString(new ClassPathResource("mock-server/azure/" + json + ".json").getInputStream(), "UTF-8"))));
	}

	private ProvQuoteInstance check(final QuoteVo quote, final double cost, final double computeCost) {
		Assertions.assertEquals(cost, quote.getCost().getMin(), DELTA);
		checkStorageS(quote.getStorages().get(0));
		checkStorageP(quote.getStorages().get(1));
		return checkInstance(quote.getInstances().get(0), computeCost);
	}

	private ProvQuoteInstance checkInstance(final ProvQuoteInstance instance, final double cost) {
		Assertions.assertEquals(cost, instance.getCost(), DELTA);
		final var price = instance.getPrice();
		Assertions.assertEquals(0,price.getInitialCost());
		Assertions.assertEquals(VmOs.LINUX, price.getOs());
		Assertions.assertEquals(ProvTenancy.SHARED, price.getTenancy());
		Assertions.assertEquals(150.278d, price.getCost(), DELTA);
		Assertions.assertEquals(5410.008, price.getCostPeriod(), DELTA);
		Assertions.assertEquals(36, price.getPeriod(), DELTA);
		final var term = price.getTerm();
		Assertions.assertEquals("three-year", term.getCode());
		Assertions.assertEquals("3 year reserved", term.getName());
		Assertions.assertFalse(term.isEphemeral());
		Assertions.assertEquals(36, term.getPeriod());
		Assertions.assertEquals("ds4v2", price.getType().getCode());
		Assertions.assertEquals("DS4 v2", price.getType().getName());
		Assertions.assertEquals("{\"series\":\"Dsv2\",\"disk\":56}", price.getType().getDescription());
		Assertions.assertTrue(price.getType().isAutoScale());
		return instance;
	}

	private ProvQuoteStorage checkStorageP(final ProvQuoteStorage storage) {
		Assertions.assertEquals(5.28d, storage.getCost(), DELTA);
		Assertions.assertEquals(1, storage.getSize(), DELTA);
		Assertions.assertNotNull(storage.getQuoteInstance());
		final var type = storage.getPrice().getType();
		Assertions.assertEquals("premiumssd-p4", type.getCode());
		Assertions.assertEquals("Premium SSD P4", type.getName());
		Assertions.assertEquals(120, type.getIops());
		Assertions.assertEquals(25, type.getThroughput());
		Assertions.assertEquals(Rate.BEST, type.getLatency());
		Assertions.assertEquals(0, storage.getPrice().getCostTransaction(), DELTA);
		Assertions.assertEquals(32, type.getMinimal());
		Assertions.assertEquals(32, type.getMaximal().intValue());
		return storage;
	}

	private ProvQuoteStorage checkStorageS(final ProvQuoteStorage storage) {
		Assertions.assertEquals(1.536d, storage.getCost(), DELTA);
		Assertions.assertEquals(32, storage.getSize(), DELTA);
		Assertions.assertNotNull(storage.getQuoteInstance());
		final var type = storage.getPrice().getType();
		Assertions.assertEquals("standardhdd-s4", type.getCode());
		Assertions.assertEquals("Standard HDD S4", type.getName());
		Assertions.assertEquals(500, type.getIops());
		Assertions.assertEquals(60, type.getThroughput());
		Assertions.assertEquals(0.05, storage.getPrice().getCostTransaction(), DELTA);
		Assertions.assertEquals(32, type.getMinimal());
		Assertions.assertEquals(32, type.getMaximal().intValue());
		Assertions.assertEquals(Rate.MEDIUM, type.getLatency());
		Assertions.assertNull(type.getOptimized());
		return storage;
	}

	/**
	 * Common offline install and configuring an instance
	 *
	 * @return The new quote from the installed
	 */
	private QuoteVo install() throws Exception {
		mockServer();

		// Check the basic quote
		return installAndConfigure();
	}

	@Test
	void installOnLine() throws Exception {
		configuration.delete(AbstractAzureImport.CONF_API_PRICES);
		configuration.put(AzurePriceImportBase.CONF_REGIONS, "europe-north");
		configuration.put(AzurePriceImportVm.CONF_ITYPE, "(ds4|a4).*");
		configuration.put(AzurePriceImportDatabase.CONF_DTYPE, "(sql-bc-gen5-16|gp-gen5-.*)");
		configuration.put(AzurePriceImportDatabase.CONF_ETYPE, "(MYSQL|POSTGRESQL|SQL SERVER)");
		configuration.put(AzurePriceImportVm.CONF_OS, "(WINDOWS|LINUX)");

		// Check the reserved
		final var quote = installAndConfigure();
		Assertions.assertTrue(quote.getCost().getMin() > 150);

		// Check the spot
		final var lookup = qiResource.lookup(subscription,
				builder().cpu(8).ram(26000).constant(true).type("ds4v2").usage("36month").build());

		Assertions.assertTrue(lookup.getCost() > 100d);
		final var instance2 = lookup.getPrice();
		Assertions.assertEquals("three-year", instance2.getTerm().getCode());
		Assertions.assertEquals("3 year reserved", instance2.getTerm().getName());
		Assertions.assertEquals("ds4v2", instance2.getType().getCode());
		Assertions.assertEquals("DS4 v2", instance2.getType().getName());
	}

	private void patchConfigurationUrl() {
		configuration.put(AbstractAzureImport.CONF_API_PRICES, "http://localhost:" + MOCK_PORT);
	}

	private int server1() {
		return qiRepository.findByName("server1").getId();
	}

	/**
	 * Install and check
	 */
	private QuoteVo installAndConfigure() throws IOException, Exception {
		resource.install(false);
		em.flush();
		em.clear();
		Assertions.assertEquals(0, provResource.getConfiguration(subscription).getCost().getMin(), DELTA);

		// Request an instance that would not be a Spot
		var lookup = qiResource.lookup(subscription,
				builder().cpu(8).ram(26000).constant(true).type("ds4v2").os(VmOs.LINUX).usage("36month").build());
		Assertions.assertEquals("europe-north/three-year/linux-ds4v2-standard", lookup.getPrice().getCode());
		Assertions.assertTrue(lookup.getPrice().getType().isAutoScale());

		// New instance for "ds4v2"
		var ivo = new QuoteInstanceEditionVo();
		ivo.setCpu(1d);
		ivo.setRam(1);
		ivo.setPrice(lookup.getPrice().getId());
		ivo.setName("server1");
		ivo.setSubscription(subscription);
		var createInstance = qiResource.create(ivo);
		Assertions.assertTrue(createInstance.getTotal().getMin() > 1);
		Assertions.assertTrue(createInstance.getId() > 0);
		
		// Lookup for "ds4v2" because of auto scaling constraint
		lookup = qiResource.lookup(subscription,
				builder().cpu(8).ram(26000).constant(true).autoScale(true).os(VmOs.LINUX).usage("dev").build());
		Assertions.assertEquals("europe-north/payg/linux-ds4v2-standard", lookup.getPrice().getCode());
		
		// Lookup for "ds4v2" because of 3 years term
		lookup = qiResource.lookup(subscription,
				builder().cpu(8).ram(26000).constant(true).autoScale(true).os(VmOs.LINUX).usage("36month").build());
		Assertions.assertEquals("europe-north/three-year/linux-ds4v2-standard", lookup.getPrice().getCode());

		// Lookup for "A4" Basic
		lookup = qiResource.lookup(subscription, builder().cpu(8).constant(true).os(VmOs.LINUX).usage("dev").build());
		Assertions.assertEquals("europe-north/payg/linux-a4-basic", lookup.getPrice().getCode());
		Assertions.assertFalse(lookup.getPrice().getType().isAutoScale());
		Assertions.assertEquals("a4-b", lookup.getPrice().getType().getCode());
		Assertions.assertEquals("A4 Basic", lookup.getPrice().getType().getName());
		
		// Create a Basic server
		ivo.setCpu(8d);
		ivo.setPrice(lookup.getPrice().getId());
		ivo.setName("serverBasic");
		final var serverBasic = qiResource.create(ivo).getId();

		// Lookup STANDARD SSD storage to a Basic instance
		// ---------------------------------
		var sLookup = qsResource.lookup(subscription,
				QuoteStorageQuery.builder().size(5).latency(Rate.LOW).instance(serverBasic).build()).get(0);
		Assertions.assertEquals(0.6, sLookup.getCost(), DELTA);
		var price = sLookup.getPrice();
		Assertions.assertEquals("europe-north/az/standardssd-e2", price.getCode());
		var type = price.getType();
		Assertions.assertEquals("standardssd-e2", type.getCode());
		Assertions.assertEquals("europe-north", price.getLocation().getName());
		Assertions.assertEquals("North Europe", price.getLocation().getDescription());

		// Lookup STANDARD SSD storage to a standard instance
		// ---------------------------------
		sLookup = qsResource
				.lookup(subscription, QuoteStorageQuery.builder().size(5).latency(Rate.LOW).instance(server1()).build())
				.get(0);
		Assertions.assertEquals(0.6, sLookup.getCost(), DELTA);
		price = sLookup.getPrice();
		type = price.getType();
		Assertions.assertEquals("standardssd-e2", type.getCode());

		// Lookup PREMIUM storage to this instance
		// ---------------------------------
		sLookup = qsResource.lookup(subscription, QuoteStorageQuery.builder().size(5).latency(Rate.BEST)
				.optimized(ProvStorageOptimized.IOPS).instance(server1()).build()).get(0);
		Assertions.assertEquals(1.44, sLookup.getCost(), DELTA);
		price = sLookup.getPrice();
		type = price.getType();
		Assertions.assertEquals("premiumssd-p2", type.getCode());

		// Lookup PREMIUM storage to a basic instance -> failed
		// ---------------------------------
		Assertions.assertEquals(0, qsResource
				.lookup(subscription, QuoteStorageQuery.builder().latency(Rate.BEST).instance(serverBasic).build())
				.size());

		// Create STANDARD HDD storage
		var svo = new QuoteStorageEditionVo();
		svo.setInstance(server1());
		svo.setSize(32);
		svo.setName("sda1");
		svo.setType("standardhdd-s4");
		svo.setSubscription(subscription);
		qsResource.create(svo);

		// Check type's specifications
		price = qsRepository.findByName("sda1").getPrice();
		type = price.getType();
		Assertions.assertEquals("standardhdd-s4", type.getCode());

		// Create PREMIUM storage
		svo = new QuoteStorageEditionVo();
		svo.setInstance(server1());
		svo.setSize(1);
		svo.setOptimized(ProvStorageOptimized.IOPS);
		svo.setType("premiumssd-p4");
		svo.setLatency(Rate.GOOD);
		svo.setName("sda2");
		svo.setSubscription(subscription);
		qsResource.create(svo);

		// Check type's specifications
		price = qsRepository.findByName("sda2").getPrice();
		type = price.getType();
		Assertions.assertEquals("premiumssd-p4", type.getCode());

		em.flush();
		em.clear();
		return provResource.getConfiguration(subscription);
	}

	/**
	 * Return the subscription identifier of the given project. Assumes there is only one subscription for a service.
	 */
	private int getSubscription(final String project) {
		return getSubscription(project, ProvAzurePluginResource.KEY);
	}
}
