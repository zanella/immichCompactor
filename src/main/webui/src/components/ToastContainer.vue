<template>
  <div class="fixed top-4 right-4 z-50 flex flex-col gap-2 max-w-sm">
    <div
      v-for="toast in toasts"
      :key="toast.id"
      class="px-4 py-3 rounded-md shadow-lg text-sm flex items-start justify-between gap-3"
      :class="{
        'bg-red-50 border border-red-200 text-red-700': toast.type === 'error',
        'bg-green-50 border border-green-200 text-green-700': toast.type === 'success',
        'bg-blue-50 border border-blue-200 text-blue-700': toast.type === 'info',
      }"
    >
      <span>{{ toast.message }}</span>
      <button class="text-current opacity-50 hover:opacity-100 font-bold" @click="remove(toast.id)">&times;</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import {useToasts} from "@/stores/toast.ts";

defineOptions({ name: 'ToastContainer' });

const { toasts, remove } = useToasts();
</script>
