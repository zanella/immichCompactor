<template>
  <div class="bg-white p-8 rounded-lg shadow-sm border border-gray-100">
    <router-link to="/" class="text-indigo-600 hover:underline text-sm">&larr; Back to users</router-link>

    <h2 class="text-3xl font-extrabold text-indigo-600 mt-4 mb-6">Add user</h2>

    <div v-if="saved" class="mb-4 px-4 py-3 bg-green-50 border border-green-200 text-green-700 rounded-md text-sm">
      User created. <router-link to="/" class="underline font-semibold">Back to users</router-link>
    </div>

    <form class="space-y-4" @submit.prevent="onSubmit">
      <div>
        <label for="name" class="block text-sm font-semibold text-gray-500 mb-1">User Name</label>
        <input id="name" v-model="form.name" type="text"
               class="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-indigo-500">
      </div>
      <div>
        <label for="immichServerUrl" class="block text-sm font-semibold text-gray-500 mb-1">Immich Server URL</label>
        <input id="immichServerUrl" v-model="form.immichServerUrl" type="text"
               class="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-indigo-500">
      </div>
      <div>
        <label for="apiKey" class="block text-sm font-semibold text-gray-500 mb-1">API Key</label>
        <input id="apiKey" v-model="form.apiKey" type="text"
               class="w-full px-3 py-2 border border-gray-300 rounded-md font-mono focus:ring-2 focus:ring-indigo-500">
      </div>
      <button type="submit" :disabled="submitting"
              class="px-6 py-2 bg-indigo-600 text-white font-semibold rounded-md hover:bg-indigo-700 transition-colors disabled:opacity-50">
        {{ submitting ? 'Creating...' : 'Create user' }}
      </button>
    </form>
  </div>
</template>

<script setup lang="ts">
import {reactive, ref} from "vue";
import {userResourceApi} from "@/integration/immichServerClient.ts";

defineOptions({ name: 'UserAdd' });

const form = reactive({ name: '', immichServerUrl: '', apiKey: '' });
const saved = ref(false);
const submitting = ref(false);

function onSubmit() {
  submitting.value = true;
  userResourceApi
    .apiUsersPost({ userAddParams: { ...form } })
    .then(() => saved.value = true)
    .finally(() => submitting.value = false);
}
</script>
