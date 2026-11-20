import { ClassValue } from "../../../utils/typescript.js";
import { PopperEffect } from "../../popper/src/popper.js";
import { PopperContentInstance } from "../../popper/src/content.js";
import { Options, Placement } from "../../popper/index.js";
import { ElTooltipContentProps } from "./content.js";
import * as _$vue from "vue";

//#region ../../packages/components/tooltip/src/content.vue.d.ts
declare var __VLS_29: {};
type __VLS_Slots = {} & {
  default?: (props: typeof __VLS_29) => any;
};
declare const __VLS_base: _$vue.DefineComponent<ElTooltipContentProps, {
  /**
   * @description el-popper-content component instance
   */
  contentRef: _$vue.Ref<PopperContentInstance | undefined, PopperContentInstance | undefined>;
  /**
   * @description validate current focus event is trigger inside el-popper-content
   */
  isFocusInsideContent: (event?: FocusEvent) => boolean | undefined;
}, {}, {}, {}, _$vue.ComponentOptionsMixin, _$vue.ComponentOptionsMixin, {}, string, _$vue.PublicProps, Readonly<ElTooltipContentProps> & Readonly<{}>, {
  style: string | false | _$vue.CSSProperties | _$vue.StyleValue[] | null;
  visible: boolean | null;
  content: string;
  offset: number;
  placement: Placement;
  popperClass: string | false | Record<string, any> | ClassValue[] | null;
  popperStyle: string | false | _$vue.CSSProperties | _$vue.StyleValue[] | null;
  teleported: boolean;
  hideAfter: number;
  effect: PopperEffect;
  virtualTriggering: boolean;
  enterable: boolean;
  pure: boolean;
  focusOnShow: boolean;
  trapping: boolean;
  stopPopperMouseEvent: boolean;
  loop: boolean;
  boundariesPadding: number;
  gpuAcceleration: boolean;
  popperOptions: Partial<Options>;
  strategy: "absolute" | "fixed";
  arrowOffset: number;
  showAfter: number;
  autoClose: number;
}, {}, {}, {}, string, _$vue.ComponentProvideOptions, false, {}, any>;
declare const __VLS_export: __VLS_WithSlots<typeof __VLS_base, __VLS_Slots>;
declare const _default: typeof __VLS_export;
type __VLS_WithSlots<T, S> = T & {
  new (): {
    $slots: S;
  };
};
//#endregion
export { _default as default };