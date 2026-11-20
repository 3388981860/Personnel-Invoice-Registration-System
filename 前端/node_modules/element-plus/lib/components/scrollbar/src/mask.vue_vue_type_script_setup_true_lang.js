const require_style = require("../../../utils/dom/style.js");
const require_index = require("../../../hooks/use-namespace/index.js");
const require_constants = require("./constants.js");
const require_mask_utils = require("./mask-utils.js");
let vue = require("vue");
//#region ../../packages/components/scrollbar/src/mask.vue?vue&type=script&setup=true&lang.ts
var mask_vue_vue_type_script_setup_true_lang_default = /*@__PURE__*/ (0, vue.defineComponent)({
	__name: "mask",
	props: { mask: {
		type: [Boolean, String],
		required: true
	} },
	setup(__props, { expose: __expose }) {
		const ns = require_index.useNamespace("scrollbar");
		const scrollbar = (0, vue.inject)(require_constants.scrollbarContextKey);
		const edges = [
			"top",
			"bottom",
			"left",
			"right"
		];
		const visible = (0, vue.shallowReactive)({
			top: false,
			bottom: false,
			left: false,
			right: false
		});
		const styles = (0, vue.shallowRef)({});
		const handleScroll = () => {
			const wrap = scrollbar.wrapElement;
			if (!wrap) return;
			const { clientHeight, scrollHeight, scrollTop, clientWidth, scrollWidth, scrollLeft } = wrap;
			const maxTop = scrollHeight - clientHeight;
			const maxLeft = scrollWidth - clientWidth;
			const hasVerticalOverflow = clientHeight > 0 && maxTop > 1;
			const hasHorizontalOverflow = clientWidth > 0 && maxLeft > 1;
			let left = scrollLeft;
			if (hasHorizontalOverflow && require_style.getStyle(wrap, "direction") === "rtl") switch (require_mask_utils.getRTLOffsetType()) {
				case "negative":
					left = maxLeft + scrollLeft;
					break;
				case "positive-ascending": left = maxLeft - scrollLeft;
			}
			visible.top = hasVerticalOverflow && scrollTop > 1;
			visible.bottom = hasVerticalOverflow && maxTop - scrollTop > 1;
			visible.left = hasHorizontalOverflow && left > 1;
			visible.right = hasHorizontalOverflow && maxLeft - left > 1;
		};
		const update = () => {
			const { wrapElement: wrap, scrollbarElement } = scrollbar;
			if (!wrap || !scrollbarElement) return;
			const { offsetLeft, offsetTop, clientLeft, clientTop, clientWidth, clientHeight } = wrap;
			const left = offsetLeft + clientLeft;
			const top = offsetTop + clientTop;
			const verticalStyle = {
				left: `${left}px`,
				width: `${clientWidth}px`,
				maxHeight: `${clientHeight}px`
			};
			const horizontalStyle = {
				top: `${top}px`,
				height: `${clientHeight}px`,
				maxWidth: `${clientWidth}px`
			};
			styles.value = {
				top: {
					...verticalStyle,
					top: `${top}px`
				},
				bottom: {
					...verticalStyle,
					bottom: `${scrollbarElement.clientHeight - top - clientHeight}px`
				},
				left: {
					...horizontalStyle,
					left: `${left}px`
				},
				right: {
					...horizontalStyle,
					right: `${scrollbarElement.clientWidth - left - clientWidth}px`
				}
			};
			handleScroll();
		};
		__expose({
			update,
			handleScroll
		});
		return (_ctx, _cache) => {
			return (0, vue.openBlock)(), (0, vue.createElementBlock)("div", {
				class: (0, vue.normalizeClass)((0, vue.unref)(ns).e("mask")),
				"aria-hidden": "true"
			}, [((0, vue.openBlock)(), (0, vue.createElementBlock)(vue.Fragment, null, (0, vue.renderList)(edges, (edge) => {
				return (0, vue.createVNode)(vue.Transition, {
					key: edge,
					name: (0, vue.unref)(ns).b("mask-fade")
				}, {
					default: (0, vue.withCtx)(() => [__props.mask === true || __props.mask === edge ? (0, vue.withDirectives)(((0, vue.openBlock)(), (0, vue.createElementBlock)("div", {
						key: 0,
						class: (0, vue.normalizeClass)((0, vue.unref)(ns).e(`${edge}-mask`)),
						style: (0, vue.normalizeStyle)(styles.value[edge]),
						"aria-hidden": "true"
					}, null, 6)), [[vue.vShow, (0, vue.unref)(visible)[edge]]]) : (0, vue.createCommentVNode)("v-if", true)]),
					_: 2
				}, 1032, ["name"]);
			}), 64))], 2);
		};
	}
});
//#endregion
exports.default = mask_vue_vue_type_script_setup_true_lang_default;

//# sourceMappingURL=mask.vue_vue_type_script_setup_true_lang.js.map