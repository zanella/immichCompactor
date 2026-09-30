<template>
  <div class="bg-white p-8 rounded-lg shadow-sm border border-gray-100">
    <router-link
      to="/users/new"
      class="inline-block mb-4 px-6 py-2 bg-indigo-600 text-white font-semibold rounded-md hover:bg-indigo-700 transition-colors"
    >
      Add User
    </router-link>

    <p v-if="loading" class="text-gray-500">Loading...</p>
    <p v-else-if="users.length === 0" class="text-gray-500">No users found.</p>

    <div v-else>
      <div
        v-for="user in users"
        :key="user._id ?? undefined"
        class="flex items-center gap-4 py-2 border-b border-gray-100"
      >
        <span>{{ user.name }}</span>
        <router-link
          :to="`/users/${user._id}`"
          class="px-4 py-1 bg-indigo-600 text-white text-sm font-semibold rounded-md hover:bg-indigo-700 transition-colors"
        >
          Info
        </router-link>
        <router-link
          :to="`/users/${user._id}/assets`"
          class="px-4 py-1 bg-indigo-600 text-white text-sm font-semibold rounded-md hover:bg-indigo-700 transition-colors"
        >
          Assets
        </router-link>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import {userResourceApi} from "@/integration/immichServerClient.ts";
import {onMounted, ref} from "vue";
import type {UserInfo} from "@/generated";

defineOptions({ name: 'UserIndex' });

const users = ref<Array<UserInfo>>([]);
const loading = ref(true);

onMounted(() => {
  userResourceApi
    .apiUsersGet()
    .then((res) => users.value = res.data)
    .finally(() => loading.value = false);
});
</script>
