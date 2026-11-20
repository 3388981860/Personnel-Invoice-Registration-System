Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });
let _vueuse_core = require("@vueuse/core");
//#region ../../packages/components/scrollbar/src/mask-utils.ts
let cachedRTLResult = null;
function getRTLOffsetType(recalculate = false) {
	if (!_vueuse_core.isClient) return "negative";
	if (cachedRTLResult === null || recalculate) {
		const outerDiv = document.createElement("div");
		const outerStyle = outerDiv.style;
		outerStyle.width = "50px";
		outerStyle.height = "50px";
		outerStyle.overflow = "scroll";
		outerStyle.direction = "rtl";
		outerStyle.position = "absolute";
		outerStyle.visibility = "hidden";
		outerStyle.top = "-9999px";
		const innerDiv = document.createElement("div");
		const innerStyle = innerDiv.style;
		innerStyle.width = "100px";
		innerStyle.height = "100px";
		outerDiv.appendChild(innerDiv);
		document.body.appendChild(outerDiv);
		if (outerDiv.scrollLeft > 0) cachedRTLResult = "positive-descending";
		else {
			outerDiv.scrollLeft = 1;
			cachedRTLResult = outerDiv.scrollLeft === 0 ? "negative" : "positive-ascending";
		}
		document.body.removeChild(outerDiv);
	}
	return cachedRTLResult;
}
//#endregion
exports.getRTLOffsetType = getRTLOffsetType;

//# sourceMappingURL=mask-utils.js.map