Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });
const require_types = require("../../../utils/types.js");
const require_runtime = require("../../../utils/vue/props/runtime.js");
const require_dialog = require("../../dialog/src/dialog.js");
let _vue_shared = require("@vue/shared");
//#region ../../packages/components/drawer/src/drawer.ts
/**
* @deprecated Removed after 3.0.0, Use `DrawerProps` instead.
*/
const drawerProps = require_runtime.buildProps({
	...require_dialog.dialogProps,
	direction: {
		type: String,
		default: "rtl",
		values: [
			"ltr",
			"rtl",
			"ttb",
			"btt"
		]
	},
	resizable: {
		type: require_runtime.definePropType([Boolean, Object]),
		default: false,
		validator: (val) => {
			if (require_types.isBoolean(val)) return true;
			return (val.minSize === void 0 || require_types.isNumber(val.minSize) || (0, _vue_shared.isString)(val.minSize)) && (val.maxSize === void 0 || require_types.isNumber(val.maxSize) || (0, _vue_shared.isString)(val.maxSize));
		}
	},
	size: {
		type: [String, Number],
		default: "30%"
	},
	withHeader: {
		type: Boolean,
		default: true
	},
	modalFade: {
		type: Boolean,
		default: true
	},
	headerAriaLevel: {
		type: String,
		default: "2"
	}
});
const drawerEmits = {
	...require_dialog.dialogEmits,
	"resize-start": (evt, size) => evt instanceof MouseEvent && typeof size === "number",
	resize: (evt, size) => evt instanceof MouseEvent && typeof size === "number",
	"resize-end": (evt, size) => evt instanceof MouseEvent && typeof size === "number"
};
//#endregion
exports.drawerEmits = drawerEmits;
exports.drawerProps = drawerProps;

//# sourceMappingURL=drawer.js.map