import {
  CosmeticOption,
  Engine,
  Request,
  RequestType,
  StringRuleList,
  TSURLFILTER_VERSION,
  setConfiguration
} from "@adguard/tsurlfilter";

declare global {
  var NPFilterRuntime: RuntimeExports | undefined;
}

type RuntimeExports = {
  CosmeticOption: typeof CosmeticOption;
  Engine: typeof Engine;
  Request: typeof Request;
  RequestType: typeof RequestType;
  StringRuleList: typeof StringRuleList;
  TSURLFILTER_VERSION: string;
  setConfiguration: typeof setConfiguration;
};

const runtime: RuntimeExports = {
  CosmeticOption,
  Engine,
  Request,
  RequestType,
  StringRuleList,
  TSURLFILTER_VERSION,
  setConfiguration
};

globalThis.NPFilterRuntime = runtime;

let engine: Engine | null = null;

function requireEngine(): Engine {
  if (!engine) {
    throw new Error("Filter engine is not initialized");
  }
  return engine;
}

function makeCss(result: ReturnType<Engine["getCosmeticResult"]>): string {
  const hidingRules = [
    ...result.elementHiding.generic,
    ...result.elementHiding.specific
  ].map((rule) => `${rule.getContent()} { display: none !important; }`);
  const cssRules = [
    ...result.CSS.generic,
    ...result.CSS.specific
  ].map((rule) => rule.getContent());
  return [...hidingRules, ...cssRules].join("\n");
}

function getElementHidingSelectors(result: ReturnType<Engine["getCosmeticResult"]>): string[] {
  return [
    ...result.elementHiding.generic,
    ...result.elementHiding.specific
  ].map((rule) => rule.getContent());
}

globalThis.NPFilterRuntimeApi = {
  init(payloadJson: string): string {
    const payload = JSON.parse(payloadJson) as { lists: string[] };
    setConfiguration({
      engine: "extension",
      version: "1.0.0",
      verbose: false
    });

    engine = Engine.createSync({
      filters: payload.lists.map((rulesText, listId) => ({
        id: listId,
        content: rulesText
      }))
    });
    return JSON.stringify({
      ok: true,
      rulesCount: engine.getRulesCount(),
      version: TSURLFILTER_VERSION
    });
  },

  match(payloadJson: string): string {
    const payload = JSON.parse(payloadJson) as {
      url: string;
      sourceUrl: string | null;
      requestType: number;
    };
    const request = new Request(payload.url, payload.sourceUrl ?? null, payload.requestType as RequestType);
    const result = requireEngine().matchRequest(request);
    const basicRule = result.getBasicResult();
    return JSON.stringify({
      blocked: !!basicRule && !basicRule.isAllowlist(),
      cosmeticOption: result.getCosmeticOption()
    });
  },

  cosmetic(payloadJson: string): string {
    const payload = JSON.parse(payloadJson) as {
      url: string;
      sourceUrl: string | null;
    };
    const request = new Request(payload.url, payload.sourceUrl ?? null, RequestType.Document);
    const result = requireEngine().matchRequest(request);
    const cosmetic = requireEngine().getCosmeticResult(request, result.getCosmeticOption() || CosmeticOption.CosmeticOptionAll);
    return JSON.stringify({
      css: makeCss(cosmetic),
      selectors: getElementHidingSelectors(cosmetic),
      cosmeticOption: result.getCosmeticOption()
    });
  }
};

declare global {
  var NPFilterRuntimeApi:
    | {
      init(payloadJson: string): string;
      match(payloadJson: string): string;
      cosmetic(payloadJson: string): string;
    }
    | undefined;
}
