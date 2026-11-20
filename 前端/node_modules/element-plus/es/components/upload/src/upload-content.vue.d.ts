import { Awaitable, Mutable } from "../../../utils/typescript.js";
import { UploadAjaxError } from "./ajax.js";
import { ListType, UploadData, UploadFile, UploadProgressEvent, UploadRawFile, UploadRequestHandler, UploadUserFile } from "./upload.js";
import { UploadContentProps } from "./upload-content.js";
import * as _$vue from "vue";

//#region ../../packages/components/upload/src/upload-content.vue.d.ts
declare var __VLS_9: {}, __VLS_11: {};
type __VLS_Slots = {} & {
  default?: (props: typeof __VLS_9) => any;
} & {
  default?: (props: typeof __VLS_11) => any;
};
declare const __VLS_base: _$vue.DefineComponent<UploadContentProps, {
  abort: (file?: UploadFile) => void;
  upload: (rawFile: UploadRawFile) => Promise<void>;
}, {}, {}, {}, _$vue.ComponentOptionsMixin, _$vue.ComponentOptionsMixin, {}, string, _$vue.PublicProps, Readonly<UploadContentProps> & Readonly<{}>, {
  data: Awaitable<Mutable<Record<string, any>>> | ((rawFile: UploadRawFile) => Awaitable<UploadData>);
  name: string;
  disabled: boolean;
  onProgress: (evt: UploadProgressEvent, rawFile: UploadRawFile) => void;
  onError: (err: UploadAjaxError, rawFile: UploadRawFile) => void;
  beforeUpload: (rawFile: UploadRawFile) => Awaitable<void | undefined | null | boolean | File | Blob>;
  onRemove: (file: UploadFile | UploadRawFile) => void;
  onSuccess: (response: any, rawFile: UploadRawFile) => unknown;
  onExceed: (files: File[], uploadFiles: UploadUserFile[]) => void;
  action: string;
  method: string;
  showFileList: boolean;
  accept: string;
  fileList: UploadUserFile[];
  autoUpload: boolean;
  listType: ListType;
  httpRequest: UploadRequestHandler;
  onStart: (rawFile: UploadRawFile) => void;
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