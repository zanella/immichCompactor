import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import './assets/main.css'
import { useToasts } from './stores/toast'

const app = createApp(App)
const { add: toast } = useToasts()

app.config.errorHandler = (err, _instance, info) => {
  console.error("Unhandled error:", err, info);
  toast(err instanceof Error ? err.message : "An unexpected error occurred.", "error");
};

window.addEventListener('unhandledrejection', (event) => {
  console.error("Unhandled promise rejection:", event.reason);
  const data = event.reason?.response?.data;
  const message =
    typeof data === 'string'
      ? data.replace(/<[^>]*>/g, '').trim()
      : data?.message
        ?? event.reason?.message
        ?? "An unexpected error occurred.";
  toast(message, "error");
});

app.use(router)

app.mount('#app')
