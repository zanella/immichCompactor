<template>
  <div class="bg-gray-50 text-gray-900 font-sans antialiased min-h-screen flex flex-col">
    <nav class="bg-indigo-600 text-white p-4 shadow-md">
      <div class="container mx-auto">
        <h1 class="text-xl font-bold">My Quarkus App</h1>
      </div>
    </nav>

    <main class="container mx-auto mt-8 p-4 flex-1">
      <router-view />
    </main>

    <footer class="text-center p-4 text-gray-400 mt-12 text-sm">
      &copy; 2026 Powered by Quarkus & Vue
    </footer>
  </div>
</template>

<script setup lang="ts">
</script>
