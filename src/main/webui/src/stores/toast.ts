import {reactive} from "vue";

export interface Toast {
  id: number;
  message: string;
  type: "error" | "success" | "info";
}

const toasts = reactive<Toast[]>([]);

let nextId = 0;

export function useToasts() {
  function add(message: string, type: Toast["type"] = "error") {
    const id = nextId++;
    toasts.push({ id, message, type });
    setTimeout(() => remove(id), 5000);
  }

  function remove(id: number) {
    const index = toasts.findIndex((t) => t.id === id);
    if (index !== -1) toasts.splice(index, 1);
  }

  return { toasts, add, remove };
}
