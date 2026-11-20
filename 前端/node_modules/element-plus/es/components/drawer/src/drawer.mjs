import { isBoolean, isNumber, isString } from "../../../utils/types.mjs";
import { buildProps, definePropType } from "../../../utils/vue/props/runtime.mjs";
import { dialogEmits, dialogProps } from "../../dialog/src/dialog.mjs";
//#region ../../packages/components/drawer/src/drawer.ts
/**
* @deprecated Removed after 3.0.0, Use `DrawerProps` instead.
*/
const drawerProps = buildProps({
	...dialogProps,
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
		type: definePropType([Boolean, Object]),
		default: false,
		validator: (val) => {
			if (isBoolean(val)) return true;
			return (val.minSize === void 0 || isNumber(val.minSize) || isString(val.minSize)) && (val.maxSize === void 0 || isNumber(val.maxSize) || isString(val.maxSize));
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
	...dialogEmits,
	"resize-start": (evt, size) => evt instanceof MouseEvent && typeof size === "number",
	resize: (evt, size) => evt instanceof MouseEvent && typeof size === "number",
	"resize-end": (evt, size) => evt instanceof MouseEvent && typeof size === "number"
};
//#endregion
export { drawerEmits, drawerProps };

//# sourceMappingURL=drawer.mjs.map