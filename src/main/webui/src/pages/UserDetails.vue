<template>
  <div class="bg-white p-8 rounded-lg shadow-sm border border-gray-100">
    <router-link to="/" class="text-indigo-600 hover:underline text-sm">&larr; Back to users</router-link>

    <p v-if="loading" class="text-gray-500 mt-4">Loading...</p>

    <template v-else-if="details">
      <div class="mt-4 mb-6 text-sm space-y-2">
        <div>
          <span class="text-gray-500">Immich server version:</span>
          <span v-if="details.serverVersion"
                class="ml-1 px-2 py-0.5 bg-indigo-50 text-indigo-700 font-mono font-semibold rounded">
            {{ details.serverVersion }}
          </span>
          <span v-else class="ml-1 px-2 py-0.5 bg-gray-100 text-gray-500 font-mono rounded">unavailable</span>
        </div>
        <div>
          <span class="text-gray-500">Immich Compactor tag ID:</span>
          <span v-if="details.tagId"
                class="ml-1 px-2 py-0.5 bg-indigo-50 text-indigo-700 font-mono font-semibold rounded">
            {{ details.tagId }}
          </span>
          <span v-else class="ml-1 px-2 py-0.5 bg-gray-100 text-gray-500 font-mono rounded">unavailable</span>
        </div>
      </div>

      <h2 class="text-3xl font-extrabold text-indigo-600 mt-4 mb-6">{{ details.userInfo.name }}</h2>

      <div v-if="saved" class="mb-4 px-4 py-3 bg-green-50 border border-green-200 text-green-700 rounded-md text-sm">
        Saved.
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
          {{ submitting ? 'Saving...' : 'Save changes' }}
        </button>
      </form>
    </template>
  </div>
</template>

<script setup lang="ts">
import {onMounted, reactive, ref} from "vue";
import {useRoute} from "vue-router";
import {userResourceApi} from "@/integration/immichServerClient.ts";
import type {UserDetails} from "@/generated";

defineOptions({ name: 'UserDetails' });

const route = useRoute();
const loading = ref(true);
const details = ref<UserDetails | null>(null);
const form = reactive({ name: '', immichServerUrl: '', apiKey: '' });
const saved = ref(false);
const submitting = ref(false);

const userId = Number(route.params.id);

onMounted(() => {
  userResourceApi
    .apiUsersIdGet({ id: route.params.id as string })
    .then((res) => {
      details.value = res.data;

      form.name = res.data.userInfo.name;
      form.immichServerUrl = res.data.userInfo.immichServerUrl;
      form.apiKey = res.data.userInfo.apiKey;
    })
    .finally(() => {
      loading.value = false;
    });
});

function onSubmit() {
  saved.value = false;
  submitting.value = true;
  userResourceApi
    .apiUsersPost({ userAddParams: { id: userId, ...form } })
    .then(() => {
      saved.value = true;
    })
    .finally(() => {
      submitting.value = false;
    });
}
</script>
