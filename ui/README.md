# plugin-prov-azure — Vue UI

Vue source for the **prov-azure** tool plugin (`service:prov:azure`), the
Azure implementation of the `prov` (Provisioning) service. Compiled by
Vite into the Maven plugin JAR at
`../src/main/resources/META-INF/resources/webjars/prov-azure/vue/`, served
by the host at `/main/prov-azure/vue/index.js`.

This is a **tool-level, i18n-only** plugin — the legacy `azure.js`
controller was an empty `define({})`, so this plugin contributes no row
actions or detail chips. Its entire UI surface is:

- **i18n** — Azure parameter labels (`service:prov:azure:*`) for the
  subscribe wizard's auto-rendered parameter form, plus the support-plan
  descriptions referenced from `csv/azure-prov-support-type.csv`.

It declares `requires: ['prov']`; the loader pulls the parent before
calling `install()` so the wizard resolves the labels on first render.

## Commands

```bash
npm install
npm run build   # → ../src/main/resources/.../webjars/prov-azure/vue/
npm run lint
npm test        # vitest — manifest + i18n contract tests
npm run dev     # standalone dev harness on :5179
```

For real integration testing, run the host's vite dev server
(`ligoj/app-ui/src/main/webapp`, `npm run dev`) which proxies
`/ligoj/main/prov-azure/vue/*` to the freshly built bundle.
