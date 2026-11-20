Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });
let _vue_shared = require("@vue/shared");
//#region ../../packages/utils/strings.ts
const kebabCase = _vue_shared.hyphenate;
/**
* fork from {@link https://github.com/sindresorhus/escape-string-regexp}
*/
const escapeStringRegexp = (string = "") => string.replace(/[|\\{}()[\]^$+*?.]/g, "\\$&").replace(/-/g, "\\x2d");
const capitalize = (str) => (0, _vue_shared.capitalize)(str);
//#endregion
Object.defineProperty(exports, "camelize", {
	enumerable: true,
	get: function() {
		return _vue_shared.camelize;
	}
});
exports.capitalize = capitalize;
exports.escapeStringRegexp = escapeStringRegexp;
Object.defineProperty(exports, "hyphenate", {
	enumerable: true,
	get: function() {
		return _vue_shared.hyphenate;
	}
});
exports.kebabCase = kebabCase;

//# sourceMappingURL=strings.js.map