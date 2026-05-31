/*
 * Contract tests for plugin-prov-azure.
 *
 * The legacy controller was empty, so this is an i18n-only tool plugin:
 * the tests assert the manifest shape, the `requires: ['prov']`
 * dependency, that install() merges the Azure parameter labels and
 * support-plan descriptions, and that feature() throws for any action
 * (there are none).
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useI18nStore } from '@ligoj/host'
import pluginProvAzureDef from '../index.js'

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('plugin-prov-azure contract', () => {
  it('exports required fields (id, label, install, feature, service, meta)', () => {
    expect(pluginProvAzureDef.id).toBe('prov-azure')
    expect(typeof pluginProvAzureDef.label).toBe('string')
    expect(typeof pluginProvAzureDef.install).toBe('function')
    expect(typeof pluginProvAzureDef.feature).toBe('function')
    expect(pluginProvAzureDef.service).toBeTypeOf('object')
    expect(pluginProvAzureDef.meta).toMatchObject({ icon: expect.any(String), color: expect.any(String) })
  })

  it('declares `requires: ["prov"]` — parent plugin must load first', () => {
    expect(pluginProvAzureDef.requires).toEqual(['prov'])
  })

  it('declares no routes — tool-level i18n augmentation only', () => {
    expect(pluginProvAzureDef.routes).toBeUndefined()
  })

  it('install() merges the Azure parameter labels and support descriptions', () => {
    const i18n = useI18nStore()
    pluginProvAzureDef.install()
    expect(i18n.t('service:prov:azure:subscription')).toBe('Subscription')
    expect(i18n.t('service:prov:azure:resource-group')).toBe('Resource Group')
    expect(i18n.t('service:prov:azure:support:business')).toContain('Business')
  })

  it('feature() throws for any action (the legacy controller was empty)', () => {
    expect(() => pluginProvAzureDef.feature('renderFeatures')).toThrow(/no feature "renderFeatures"/)
    expect(() => pluginProvAzureDef.feature('unknown')).toThrow(/no feature "unknown"/)
  })
})
