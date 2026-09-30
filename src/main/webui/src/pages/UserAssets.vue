<template>
  <div class="bg-white p-8 rounded-lg shadow-sm border border-gray-100">
    <router-link to="/" class="text-indigo-600 hover:underline text-sm">&larr; Back to users</router-link>

    <h2 class="text-3xl font-extrabold text-indigo-600 mt-4 mb-6">Assets &mdash; {{ user?.name ?? 'Loading...' }}</h2>

    <p v-if="!user" class="text-gray-500">Loading...</p>
  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from "vue";
import {useRoute} from "vue-router";
import {userResourceApi} from "@/integration/immichServerClient.ts";
import type {UserInfo} from "@/generated";

defineOptions({ name: 'UserAssets' });

const route = useRoute();
const user = ref<UserInfo | null>(null);

onMounted(() => {
  userResourceApi
    .apiUsersGet()
    .then((res) => {
      const id = Number(route.params.id);
      user.value = res.data.find((u) => u.id === id) ?? null;
    });
});
</script>
