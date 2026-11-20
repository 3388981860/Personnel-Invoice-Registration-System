import { Awaitable, Mutable } from "../../../utils/typescript.js";
import { ListType, UploadData, UploadFile, UploadFiles, UploadProgressEvent, UploadProps, UploadRawFile, UploadRequestHandler, UploadStatus, UploadUserFile } from "./upload.js";
import * as _$vue from "vue";

//#region ../../packages/components/upload/src/upload.vue.d.ts
declare var __VLS_10: {
    file: UploadFile;
    index: number;
  }, __VLS_19: {}, __VLS_21: {}, __VLS_29: {}, __VLS_31: {}, __VLS_33: {}, __VLS_35: {}, __VLS_46: {
    file: UploadFile;
    index: number;
  };
type __VLS_Slots = {} & {
  file?: (props: typeof __VLS_10) => any;
} & {
  trigger?: (props: typeof __VLS_19) => any;
} & {
  default?: (props: typeof __VLS_21) => any;
} & {
  trigger?: (props: typeof __VLS_29) => any;
} & {
  default?: (props: typeof __VLS_31) => any;
} & {
  default?: (props: typeof __VLS_33) => any;
} & {
  tip?: (props: typeof __VLS_35) => any;
} & {
  file?: (props: typeof __VLS_46) => any;
};
declare const __VLS_base: _$vue.DefineComponent<UploadProps, {
  /** @description cancel upload request */abort: (file?: UploadFile) => void; /** @description upload the file list manually */
  submit: () => void; /** @description clear the file list  */
  clearFiles: (states?: UploadStatus[]) => void; /** @description select the file manually */
  handleStart: (rawFile: UploadRawFile) => void; /** @description remove the file manually */
  handleRemove: (file: UploadFile | UploadRawFile) => void;
}, {}, {}, {}, _$vue.ComponentOptionsMixin, _$vue.ComponentOptionsMixin, {}, string, _$vue.PublicProps, Readonly<UploadProps> & Readonly<{}>, {
  data: Awaitable<Mutable<Record<string, any>>> | ((rawFile: UploadRawFile) => Awaitable<UploadData>);
  name: string;
  disabled: boolean;
  onChange: (uploadFile: UploadFile, uploadFiles: UploadFiles) => void;
  onProgress: (evt: UploadProgressEvent, uploadFile: UploadFile, uploadFiles: UploadFiles) => void;
  onError: (error: Error, uploadFile: UploadFile, uploadFiles: UploadFiles) => void;
  beforeUpload: (rawFile: UploadRawFile) => Awaitable<void | undefined | null | boolean | File | Blob>;
  onRemove: (uploadFile: UploadFile, uploadFiles: UploadFiles) => void;
  onPreview: (uploadFile: UploadFile) => void;
  onSuccess: (response: any, uploadFile: UploadFile, uploadFiles: UploadFiles) => void;
  onExceed: (files: File[], uploadFiles: UploadUserFile[]) => void;
  action: string;
  method: string;
  showFileList: boolean;
  accept: string;
  fileList: UploadUserFile[];
  autoUpload: boolean;
  listType: ListType;
  httpRequest: UploadRequestHandler;
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