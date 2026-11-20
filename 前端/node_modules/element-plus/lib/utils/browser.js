Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });
let _vueuse_core = require("@vueuse/core");
//#region ../../packages/utils/browser.ts
const isFirefox = () => _vueuse_core.isClient && /firefox/i.test(window.navigator.userAgent);
const isAndroid = () => _vueuse_core.isClient && /android/i.test(window.navigator.userAgent);
//#endregion
exports.isAndroid = isAndroid;
Object.defineProperty(exports, "isClient", {
	enumerable: true,
	get: function() {
		return _vueuse_core.isClient;
	}
});
exports.isFirefox = isFirefox;
Object.defineProperty(exports, "isIOS", {
	enumerable: true,
	get: function() {
		return _vueuse_core.isIOS;
	}
});

//# sourceMappingURL=browser.js.map