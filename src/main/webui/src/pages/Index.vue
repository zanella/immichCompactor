<template>
  <p> SVSSDFSDFSDFSDF </p>

  <ul>
    <li v-for="user in users" :key="user.id">
      <p> {{user}} {{ user.name }}</p>
    </li>
  </ul>
</template>

<script setup lang="ts">
import {userResourceApi} from "@/integration/immichServerClient.ts";
import {onMounted, ref} from "vue";
import type {UserInfo} from "@/generated";

const users = ref<Array<UserInfo>>([]);

onMounted(() => {
  userResourceApi
    .apiUsersGet()
    .then((res) => {
      console.log(res.data);

      users.value = res.data;
    })
    // TODO: add global error handler
    .catch((err) => console.error(err));
});
</script>

<style scoped></style>
