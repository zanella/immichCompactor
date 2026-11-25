import {
  Configuration, UserResourceApi,
} from '@/generated';

// Same origin as the page itself, so requests work from localhost, 0.0.0.0 or any host the jar is served on
const apiClientsCfg = new Configuration({
  basePath: window.location.origin,
  baseOptions: {
    headers: {
      "Accept": "application/json",
      "Content-Type": "application/json"
    }
  }
});

export const userResourceApi = new UserResourceApi(apiClientsCfg);
