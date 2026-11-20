import { ClassValue } from "../../../utils/typescript.js";
import { DialogTransition } from "../../dialog/src/dialog.js";
import { DrawerProps } from "./drawer.js";
import * as _$vue from "vue";

//#region ../../packages/components/drawer/src/drawer.vue.d.ts
declare var __VLS_36: {
    close: () => void;
    titleId: string;
    titleClass: string;
  }, __VLS_38: {}, __VLS_51: {}, __VLS_53: {};
type __VLS_Slots = {} & {
  header?: (props: typeof __VLS_36) => any;
} & {
  title?: (props: typeof __VLS_38) => any;
} & {
  default?: (props: typeof __VLS_51) => any;
} & {
  footer?: (props: typeof __VLS_53) => any;
};
declare const __VLS_base: _$vue.DefineComponent<DrawerProps, {
  handleClose: () => void;
}, {}, {}, {}, _$vue.ComponentOptionsMixin, _$vue.ComponentOptionsMixin, {
  open: () => void;
  "update:modelValue": (value: boolean) => void;
  close: () => void;
  resize: (evt: MouseEvent, size: number) => void;
  opened: () => void;
  closed: () => void;
  openAutoFocus: () => void;
  closeAutoFocus: () => void;
  "resize-start": (evt: MouseEvent, size: number) => void;
  "resize-end": (evt: MouseEvent, size: number) => void;
}, string, _$vue.PublicProps, Readonly<DrawerProps> & Readonly<{
  onOpen?: (() => any) | undefined;
  "onUpdate:modelValue"?: ((value: boolean) => any) | undefined;
  onClose?: (() => any) | undefined;
  onResize?: ((evt: MouseEvent, size: number) => any) | undefined;
  onOpened?: (() => any) | undefined;
  onClosed?: (() => any) | undefined;
  onOpenAutoFocus?: (() => any) | undefined;
  onCloseAutoFocus?: (() => any) | undefined;
  "onResize-start"?: ((evt: MouseEvent, size: number) => any) | undefined;
  "onResize-end"?: ((evt: MouseEvent, size: number) => any) | undefined;
}>, {
  direction: "ltr" | "rtl" | "ttb" | "btt";
  size: string | number;
  showClose: boolean;
  appendTo: string | HTMLElement;
  title: string;
  transition: DialogTransition;
  overflow: boolean;
  alignCenter: boolean;
  headerClass: string | false | Record<string, any> | ClassValue[] | null;
  closeOnPressEscape: boolean;
  draggable: boolean;
  bodyClass: string | false | Record<string, any> | ClassValue[] | null;
  footerClass: string | false | Record<string, any> | ClassValue[] | null;
  ariaLevel: string;
  closeOnClickModal: boolean;
  lockScroll: boolean;
  modal: boolean;
  openDelay: number;
  closeDelay: number;
  modalClass: string | false | Record<string, any> | ClassValue[] | null;
  headerAriaLevel: string;
  withHeader: boolean;
  modalFade: boolean;
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