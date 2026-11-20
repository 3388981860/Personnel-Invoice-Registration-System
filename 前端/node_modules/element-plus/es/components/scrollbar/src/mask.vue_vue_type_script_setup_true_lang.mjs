import { getStyle } from "../../../utils/dom/style.mjs";
import { useNamespace } from "../../../hooks/use-namespace/index.mjs";
import { scrollbarContextKey } from "./constants.mjs";
import { getRTLOffsetType } from "./mask-utils.mjs";
import { Fragment, Transition, createCommentVNode, createElementBlock, createVNode, defineComponent, inject, normalizeClass, normalizeStyle, openBlock, renderList, shallowReactive, shallowRef, unref, vShow, withCtx, withDirectives } from "vue";
//#region ../../packages/components/scrollbar/src/mask.vue?vue&type=script&setup=true&lang.ts
var mask_vue_vue_type_script_setup_true_lang_default = /*@__PURE__*/ defineComponent({
	__name: "mask",
	props: { mask: {
		type: [Boolean, String],
		required: true
	} },
	setup(__props, { expose: __expose }) {
		const ns = useNamespace("scrollbar");
		const scrollbar = inject(scrollbarContextKey);
		const edges = [
			"top",
			"bottom",
			"left",
			"right"
		];
		const visible = shallowReactive({
			top: false,
			bottom: false,
			left: false,
			right: false
		});
		const styles = shallowRef({});
		const handleScroll = () => {
			const wrap = scrollbar.wrapElement;
			if (!wrap) return;
			const { clientHeight, scrollHeight, scrollTop, clientWidth, scrollWidth, scrollLeft } = wrap;
			const maxTop = scrollHeight - clientHeight;
			const maxLeft = scrollWidth - clientWidth;
			const hasVerticalOverflow = clientHeight > 0 && maxTop > 1;
			const hasHorizontalOverflow = clientWidth > 0 && maxLeft > 1;
			let left = scrollLeft;
			if (hasHorizontalOverflow && getStyle(wrap, "direction") === "rtl") switch (getRTLOffsetType()) {
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
			return openBlock(), createElementBlock("div", {
				class: normalizeClass(unref(ns).e("mask")),
				"aria-hidden": "true"
			}, [(openBlock(), createElementBlock(Fragment, null, renderList(edges, (edge) => {
				return createVNode(Transition, {
					key: edge,
					name: unref(ns).b("mask-fade")
				}, {
					default: withCtx(() => [__props.mask === true || __props.mask === edge ? withDirectives((openBlock(), createElementBlock("div", {
						key: 0,
						class: normalizeClass(unref(ns).e(`${edge}-mask`)),
						style: normalizeStyle(styles.value[edge]),
						"aria-hidden": "true"
					}, null, 6)), [[vShow, unref(visible)[edge]]]) : createCommentVNode("v-if", true)]),
					_: 2
				}, 1032, ["name"]);
			}), 64))], 2);
		};
	}
});
//#endregion
export { mask_vue_vue_type_script_setup_true_lang_default as default };

//# sourceMappingURL=mask.vue_vue_type_script_setup_true_lang.mjs.map