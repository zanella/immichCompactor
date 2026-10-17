<template>
  <div class="bg-white p-8 rounded-lg shadow-sm border border-gray-100">
    <router-link to="/" class="text-indigo-600 hover:underline text-sm">&larr; Back to users</router-link>

    <div class="flex items-center justify-between mt-4 mb-6">
      <h2 class="text-3xl font-extrabold text-indigo-600">
        Trash &mdash; {{ user?.name ?? 'Loading...' }}
      </h2>
      <button
        :disabled="loading"
        class="px-4 py-2 bg-indigo-600 text-white text-sm font-semibold rounded-md hover:bg-indigo-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
        @click="fetchTrashed"
      >
        {{ loading ? 'Refreshing...' : 'Refresh' }}
      </button>
    </div>

    <p v-if="loading && !trashedAssets" class="text-gray-500">Loading...</p>
    <p v-else-if="trashedAssets && trashedAssets.assets.total === 0" class="text-gray-500">No trashed assets.</p>

    <div v-if="trashedAssets && trashedAssets.assets.total > 0" class="mt-6">
      <span class="text-sm text-gray-500">{{ trashedAssets.assets.total }} trashed asset(s)</span>

      <div class="overflow-x-auto mt-4">
        <table class="w-full text-sm border-collapse whitespace-nowrap">
          <thead>
            <tr class="border-b border-gray-200 bg-gray-50">
              <th class="text-left py-2 px-3 text-gray-600 font-medium w-12">#</th>
              <th class="text-left py-2 px-3 text-gray-600 font-medium">Asset ID</th>
              <th class="text-left py-2 px-3 text-gray-600 font-medium">File Name</th>
              <th class="text-left py-2 px-3 text-gray-600 font-medium w-28">Type</th>
              <th class="text-left py-2 px-3 text-gray-600 font-medium">Trashed At</th>
              <th class="text-right py-2 px-3 text-gray-600 font-medium"></th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="(asset, index) in trashedAssets.assets.items"
              :key="asset.id"
              class="border-b border-gray-100 hover:bg-gray-50"
            >
              <td class="py-2 px-3 text-gray-400 text-xs">{{ index + 1 }}</td>
              <td class="py-2 px-3 font-mono text-xs text-gray-700">{{ asset.id }}</td>
              <td class="py-2 px-3 text-gray-700">{{ asset.originalFileName }}</td>
              <td class="py-2 px-3">
                <span
                  class="inline-block px-2 py-0.5 text-xs font-semibold rounded-full"
                  :class="{
                    'bg-blue-100 text-blue-800': asset.originalMimeType?.startsWith('video/'),
                    'bg-green-100 text-green-800': asset.originalMimeType?.startsWith('image/'),
                    'bg-gray-100 text-gray-600': !asset.originalMimeType?.startsWith('video/') && !asset.originalMimeType?.startsWith('image/'),
                  }"
                >
                  {{ asset.originalMimeType ?? 'unknown' }}
                </span>
              </td>
              <td class="py-2 px-3 text-gray-500 text-xs">{{ asset.updatedAt }}</td>
              <td class="py-2 px-3 text-right whitespace-nowrap">
                <a
                  :href="`${user?.immichServerUrl}/photos/${asset.id}`"
                  target="_blank" rel="noopener noreferrer"
                  class="inline-flex items-center gap-1 px-2 py-1 bg-indigo-600 text-white text-xs font-semibold rounded hover:bg-indigo-700 transition-colors whitespace-nowrap"
                >View</a>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from "vue";
import {useRoute} from "vue-router";
import {userResourceApi} from "@/integration/immichServerClient.ts";
import type {UserInfo} from "@/generated";

defineOptions({ name: 'UserTrash' });

const route = useRoute();
const userId = route.params.id as string;

const user = ref<UserInfo | null>(null);
const trashedAssets = ref<any>(null);
const loading = ref(false);

function fetchTrashed() {
  loading.value = true;
  fetch(`/api/users/${userId}/assets/trashed`)
    .then(res => res.json())
    .then(data => { trashedAssets.value = data; })
    .finally(() => { loading.value = false; });
}

onMounted(() => {
  userResourceApi
    .apiUsersIdGet({ id: userId })
    .then((res) => {
      user.value = res.data.userInfo;
    });

  fetchTrashed();
});
</script>
