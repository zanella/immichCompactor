<template>
  <div class="bg-white p-8 rounded-lg shadow-sm border border-gray-100">
    <router-link to="/" class="text-indigo-600 hover:underline text-sm">&larr; Back to users</router-link>

    <div class="flex items-center justify-between mt-4 mb-6">
      <h2 class="text-3xl font-extrabold text-indigo-600">
        Assets &mdash; {{ user?.name ?? 'Loading...' }}
      </h2>
      <button
        :disabled="refreshing"
        class="px-4 py-2 bg-indigo-600 text-white text-sm font-semibold rounded-md hover:bg-indigo-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
        @click="triggerRefresh"
      >
        {{ refreshing ? 'Refreshing...' : 'Refresh' }}
      </button>
    </div>

    <div v-if="showStatus && refreshStatus?.state === 'RUNNING'"
         class="mt-4 mb-6 px-4 py-3 bg-blue-50 border border-blue-200 text-blue-700 rounded-md text-sm">
      Scanning Immich and queuing assets...
    </div>
    <div v-else-if="showStatus && refreshStatus?.state === 'DONE'"
         class="mt-4 mb-6 px-4 py-3 bg-green-50 border border-green-200 text-green-700 rounded-md text-sm">
      Found {{ refreshStatus.assetsFound }} asset(s); queued {{ refreshStatus.assetsQueued }} new one(s) for conversion.
    </div>
    <div v-else-if="showStatus && refreshStatus?.state === 'FAILED'"
         class="mt-4 mb-6 px-4 py-3 bg-red-50 border border-red-200 text-red-700 rounded-md text-sm">
      Refresh failed: {{ refreshStatus.error }}
    </div>

    <div v-if="queuedData" class="mt-6">
      <div class="flex items-center justify-between mb-4 flex-wrap gap-4">
        <div class="flex items-center gap-4 flex-wrap">
          <div class="flex items-center gap-2">
            <label for="page-size" class="text-sm text-gray-600 whitespace-nowrap">Page size:</label>
            <select
              id="page-size"
              :value="pageSize"
              class="border border-gray-300 rounded-md px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-indigo-500"
              @change="onPageSizeChange"
            >
              <option v-for="s in pageSizeOptions" :key="s" :value="s">{{ s }}</option>
            </select>
          </div>
          <div class="flex items-center gap-3">
            <span class="text-sm text-gray-600">Content Type:</span>
            <label
              v-for="ct in queuedData.contentTypes"
              :key="ct"
              class="flex items-center gap-1 text-sm cursor-pointer select-none whitespace-nowrap"
            >
              <input
                type="checkbox"
                :value="ct"
                :checked="selectedContentTypes.includes(ct)"
                class="rounded border-gray-300 text-indigo-600 focus:ring-indigo-500"
                @change="onContentTypeToggle(ct)"
              >
              {{ ct }}
            </label>
          </div>
        </div>
        <button
          :disabled="batchConverting || queuedData.assets.length === 0"
          class="px-4 py-2 bg-emerald-600 text-white text-sm font-semibold rounded-md hover:bg-emerald-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed whitespace-nowrap"
          @click="batchConvert"
        >
          {{ batchConverting ? batchConvertLabel : 'Batch convert' }}
        </button>
        <span class="text-sm text-gray-500">{{ queuedData.total }} queued asset(s)</span>
      </div>

      <p v-if="queuedData.assets.length === 0" class="text-gray-500 text-sm italic">No queued assets.</p>

      <div v-else class="overflow-x-auto">
        <table class="w-full text-sm border-collapse whitespace-nowrap">
          <thead>
            <tr class="border-b border-gray-200 bg-gray-50">
              <th class="text-left py-2 px-3 text-gray-600 font-medium w-12">#</th>
              <th class="text-left py-2 px-3 text-gray-600 font-medium">Asset ID</th>
              <th class="text-left py-2 px-3 text-gray-600 font-medium w-28 whitespace-nowrap">Content Type</th>
              <th class="text-left py-2 px-3 text-gray-600 font-medium w-28">State</th>
              <th class="text-right py-2 px-3 text-gray-600 font-medium"></th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="(asset, index) in queuedData.assets"
              :key="asset.assetId"
              class="border-b border-gray-100 hover:bg-gray-50"
              :class="{ 'opacity-40 pointer-events-none select-none': convertResults.has(asset.assetId) && convertResults.get(asset.assetId)?.status === 'removed' }"
            >
              <td class="py-2 px-3 text-gray-400 text-xs">{{ (queuedData.page - 1) * queuedData.size + index + 1 }}</td>
              <td class="py-2 px-3 font-mono text-xs text-gray-700" :class="{ 'line-through': convertResults.has(asset.assetId) }">
                {{ asset.assetId }}
              </td>
              <td class="py-2 px-3">
                <span
                  class="inline-block px-2 py-0.5 text-xs font-semibold rounded-full"
                  :class="{
                    'bg-blue-100 text-blue-800': asset.contentType === 'VIDEO',
                    'bg-green-100 text-green-800': asset.contentType === 'IMAGE',
                    'bg-gray-100 text-gray-600': asset.contentType !== 'VIDEO' && asset.contentType !== 'IMAGE',
                  }"
                >
                  {{ asset.contentType }}
                </span>
              </td>
              <td class="py-2 px-3">
                <span v-if="convertResults.has(asset.assetId)"
                      class="inline-block px-2 py-0.5 text-xs font-semibold rounded-full"
                      :class="{
                        'bg-green-100 text-green-800': convertResults.get(asset.assetId)?.status === 'converted',
                        'bg-gray-100 text-gray-600': convertResults.get(asset.assetId)?.status === 'removed',
                        'bg-red-100 text-red-800': convertResults.get(asset.assetId)?.status === 'error',
                      }">
                  {{ convertResults.get(asset.assetId)?.status === 'converted' ? 'Converted' : convertResults.get(asset.assetId)?.status === 'removed' ? 'Removed' : 'Error' }}
                </span>
                <span v-else
                      class="inline-block px-2 py-0.5 text-xs font-semibold rounded-full"
                      :class="asset.currentState === 'QUEUED' ? 'bg-amber-100 text-amber-800' : 'bg-gray-100 text-gray-600'">
                  {{ asset.currentState }}
                </span>
              </td>
              <td class="py-2 px-3 text-right whitespace-nowrap">
                <div class="inline-flex items-center gap-2">
                  <template v-if="convertResults.has(asset.assetId) && convertResults.get(asset.assetId)?.status === 'converted'">
                    <a
                      :href="`${convertResults.get(asset.assetId)?.immichServerUrl}/photos/${asset.assetId}`"
                      target="_blank" rel="noopener noreferrer"
                      class="inline-flex items-center gap-1 px-2 py-1 border-2 border-red-500 text-red-700 bg-white text-xs font-bold rounded hover:bg-red-50 transition-colors whitespace-nowrap"
                    >View OLD</a>
                    <a
                      :href="`${convertResults.get(asset.assetId)?.immichServerUrl}/photos/${convertResults.get(asset.assetId)?.newAssetId}`"
                      target="_blank" rel="noopener noreferrer"
                      class="inline-flex items-center gap-1 px-2 py-1 bg-indigo-600 text-white text-xs font-semibold rounded hover:bg-indigo-700 transition-colors whitespace-nowrap"
                    >View new</a>
                  </template>
                  <template v-else-if="!convertResults.has(asset.assetId)">
                    <a
                      :href="`${user?.immichServerUrl}/photos/${asset.assetId}`"
                      target="_blank" rel="noopener noreferrer"
                      class="inline-flex items-center gap-1 px-2 py-1 bg-indigo-600 text-white text-xs font-semibold rounded hover:bg-indigo-700 transition-colors whitespace-nowrap"
                    >View</a>
                    <button
                      :disabled="convertingIds.has(asset.assetId)"
                      class="inline-flex items-center justify-center gap-1 min-w-16 px-2 py-1 bg-emerald-600 text-white text-xs font-semibold rounded hover:bg-emerald-700 disabled:opacity-70 disabled:cursor-wait transition-colors"
                      @click="convertAsset(asset.assetId)"
                    >
                      <span v-if="convertingIds.has(asset.assetId)">...</span>
                      <span v-else>Convert</span>
                    </button>
                  </template>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="queuedData.pageCount > 1" class="flex items-center justify-center gap-3 mt-4">
        <button
          :disabled="!queuedData.hasPrev"
          class="px-3 py-1 text-sm font-semibold rounded-md transition-colors"
          :class="queuedData.hasPrev ? 'bg-indigo-600 text-white hover:bg-indigo-700' : 'bg-gray-200 text-gray-400 cursor-not-allowed'"
          @click="goToPage(queuedData.page - 1)"
        >
          &laquo; Prev
        </button>
        <span class="text-sm text-gray-600">Page {{ queuedData.page }} of {{ queuedData.pageCount }}</span>
        <button
          :disabled="!queuedData.hasNext"
          class="px-3 py-1 text-sm font-semibold rounded-md transition-colors"
          :class="queuedData.hasNext ? 'bg-indigo-600 text-white hover:bg-indigo-700' : 'bg-gray-200 text-gray-400 cursor-not-allowed'"
          @click="goToPage(queuedData.page + 1)"
        >
          Next &raquo;
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import {computed, onMounted, onUnmounted, reactive, ref} from "vue";
import {useRoute} from "vue-router";
import {userResourceApi} from "@/integration/immichServerClient.ts";
import type {ConvertResultResponse, JobStatus, QueuedAssetsResponse, UserInfo} from "@/generated";

defineOptions({ name: 'UserAssets' });

const route = useRoute();
const userId = route.params.id as string;

const user = ref<UserInfo | null>(null);
const refreshStatus = ref<JobStatus | null>(null);
const queuedData = ref<QueuedAssetsResponse | null>(null);
const pageSizeOptions = [10, 25, 50, 100, 1000];
const pageSize = ref(50);
const selectedContentTypes = reactive<string[]>([]);
const convertResults = reactive(new Map<string, ConvertResultResponse>());
const convertingIds = reactive(new Set<string>());
const batchConverting = ref(false);
const batchConvertLabel = ref('Batch convert');
const triggerInFlight = ref(false);
const showStatus = ref(false);

let pollTimer: ReturnType<typeof setInterval> | null = null;

const refreshing = computed(() => triggerInFlight.value || refreshStatus.value?.state === 'RUNNING');

function fetchStatus() {
  userResourceApi
    .apiUsersIdAssetsStatusGet({ id: userId })
    .then((res) => {
      refreshStatus.value = res.data;
      if (res.data.state === 'RUNNING' && !pollTimer) {
        showStatus.value = true;
        triggerInFlight.value = false;
        pollTimer = setInterval(fetchStatus, 2000);
      } else if (res.data.state !== 'RUNNING' && pollTimer) {
        clearInterval(pollTimer);
        pollTimer = null;
        triggerInFlight.value = false;
        fetchQueued();
      }
    });
}

function fetchQueued() {
  userResourceApi
    .apiUsersIdAssetsQueuedGet({
      id: userId,
      page: queuedData.value?.page ?? 1,
      size: pageSize.value,
      contentTypes: selectedContentTypes.length > 0 ? selectedContentTypes : null,
    })
    .then((res) => {
      queuedData.value = res.data;
    });
}

function triggerRefresh() {
  triggerInFlight.value = true;
  showStatus.value = true;
  userResourceApi
    .apiUsersIdAssetsRefreshPost({ id: userId })
    .then(() => fetchStatus());
}

function onPageSizeChange(e: Event) {
  pageSize.value = Number((e.target as HTMLSelectElement).value);
  if (queuedData.value) queuedData.value.page = 1;
  fetchQueued();
}

function onContentTypeToggle(ct: string) {
  const idx = selectedContentTypes.indexOf(ct);
  if (idx >= 0) {
    selectedContentTypes.splice(idx, 1);
  } else {
    selectedContentTypes.push(ct);
  }
  if (queuedData.value) queuedData.value.page = 1;
  fetchQueued();
}

function goToPage(page: number) {
  if (queuedData.value) queuedData.value.page = page;
  fetchQueued();
}

function convertAsset(assetId: string) {
  convertingIds.add(assetId);
  userResourceApi
    .apiUsersIdAssetsAssetIdConvertPost({ id: userId, assetId })
    .then((res) => {
      convertResults.set(assetId, res.data);
    })
    .finally(() => {
      convertingIds.delete(assetId);
    });
}

async function batchConvert() {
  if (!queuedData.value) return;
  batchConverting.value = true;
  const assets = [...queuedData.value.assets];
  const total = assets.length;
  let i = 0;
  for (const asset of assets) {
    if (convertResults.has(asset.assetId)) { i++; continue; }
    i++;
    batchConvertLabel.value = `Converting ${i}/${total}...`;
    convertingIds.add(asset.assetId);
    try {
      const res = await userResourceApi.apiUsersIdAssetsAssetIdConvertPost({ id: userId, assetId: asset.assetId });
      convertResults.set(asset.assetId, res.data);
    } finally {
      convertingIds.delete(asset.assetId);
    }
  }
  batchConverting.value = false;
  batchConvertLabel.value = 'Batch convert';
}

onMounted(() => {
  userResourceApi
    .apiUsersIdGet({ id: userId })
    .then((res) => {
      user.value = res.data.userInfo;
    });

  fetchStatus();

  fetchQueued();
});

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer);
});
</script>
