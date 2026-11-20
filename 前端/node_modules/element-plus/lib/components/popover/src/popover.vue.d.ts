import { Arrayable, ClassValue } from "../../../utils/typescript.js";
import { PopperEffect, PopperInstance } from "../../popper/src/popper.js";
import { Options, Placement } from "../../popper/index.js";
import { TooltipTriggerType } from "../../tooltip/src/trigger.js";
import { PopoverProps } from "./popover.js";
import * as _$vue from "vue";

//#region ../../packages/components/popover/src/popover.vue.d.ts
declare var __VLS_12: {}, __VLS_15: {
    hide: () => void;
  };
type __VLS_Slots = {} & {
  reference?: (props: typeof __VLS_12) => any;
} & {
  default?: (props: typeof __VLS_15) => any;
};
declare const __VLS_base: _$vue.DefineComponent<PopoverProps, {
  /** @description popper ref */popperRef: _$vue.ComputedRef<PopperInstance | undefined>; /** @description hide popover */
  hide: () => void;
}, {}, {}, {}, _$vue.ComponentOptionsMixin, _$vue.ComponentOptionsMixin, {
  "update:visible": (value: boolean) => void;
  "before-enter": () => void;
  "before-leave": () => void;
  "after-enter": () => void;
  "after-leave": () => void;
}, string, _$vue.PublicProps, Readonly<PopoverProps> & Readonly<{
  "onUpdate:visible"?: ((value: boolean) => any) | undefined;
  "onBefore-enter"?: (() => any) | undefined;
  "onBefore-leave"?: (() => any) | undefined;
  "onAfter-enter"?: (() => any) | undefined;
  "onAfter-leave"?: (() => any) | undefined;
}>, {
  style: string | false | _$vue.CSSProperties | _$vue.StyleValue[] | null;
  width: string | number;
  visible: boolean | null;
  content: string;
  offset: number;
  placement: Placement;
  title: string;
  popperClass: string | false | Record<string, any> | ClassValue[] | null;
  popperStyle: string | false | _$vue.CSSProperties | _$vue.StyleValue[] | null;
  teleported: boolean;
  hideAfter: number;
  effect: PopperEffect;
  persistent: boolean;
  virtualTriggering: boolean;
  enterable: boolean;
  pure: boolean;
  focusOnShow: boolean;
  trapping: boolean;
  stopPopperMouseEvent: boolean;
  loop: boolean;
  boundariesPadding: number;
  popperOptions: Partial<Options>;
  strategy: "absolute" | "fixed";
  arrowOffset: number;
  showArrow: boolean;
  showAfter: number;
  autoClose: number;
  trigger: Arrayable<TooltipTriggerType>;
  triggerKeys: string[];
  tabindex: string | number;
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