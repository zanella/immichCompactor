import { createRouter, createWebHistory } from 'vue-router'
import Index from "@/pages/Index.vue";
import UserAdd from "@/pages/UserAdd.vue";
import UserDetails from "@/pages/UserDetails.vue";
import UserAssets from "@/pages/UserAssets.vue";

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', name: 'Index', component: Index },
    { path: '/users/new', name: 'UserAdd', component: UserAdd },
    { path: '/users/:id', name: 'UserDetails', component: UserDetails },
    { path: '/users/:id/assets', name: 'UserAssets', component: UserAssets },
  ],
})

export default router
