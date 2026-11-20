import { ClassValue } from "../../../utils/typescript.js";
import { DialogProps, DialogTransition } from "./dialog.js";
import * as _$vue from "vue";

//#region ../../packages/components/dialog/src/dialog.vue.d.ts
declare var __VLS_40: {
    close: () => void;
    titleId: string;
    titleClass: string;
  }, __VLS_42: {}, __VLS_44: {}, __VLS_47: {};
type __VLS_Slots = {} & {
  header?: (props: typeof __VLS_40) => any;
} & {
  title?: (props: typeof __VLS_42) => any;
} & {
  default?: (props: typeof __VLS_44) => any;
} & {
  footer?: (props: typeof __VLS_47) => any;
};
declare const __VLS_base: _$vue.DefineComponent<DialogProps, {
  /** @description whether the dialog is visible */visible: _$vue.Ref<boolean, boolean>;
  dialogContentRef: _$vue.Ref<any, any>;
  resetPosition: () => void;
  handleClose: () => void;
}, {}, {}, {}, _$vue.ComponentOptionsMixin, _$vue.ComponentOptionsMixin, {
  open: () => void;
  "update:modelValue": (value: boolean) => void;
  close: () => void;
  opened: () => void;
  closed: () => void;
  openAutoFocus: () => void;
  closeAutoFocus: () => void;
}, string, _$vue.PublicProps, Readonly<DialogProps> & Readonly<{
  onOpen?: (() => any) | undefined;
  "onUpdate:modelValue"?: ((value: boolean) => any) | undefined;
  onClose?: (() => any) | undefined;
  onOpened?: (() => any) | undefined;
  onClosed?: (() => any) | undefined;
  onOpenAutoFocus?: (() => any) | undefined;
  onCloseAutoFocus?: (() => any) | undefined;
}>, {
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