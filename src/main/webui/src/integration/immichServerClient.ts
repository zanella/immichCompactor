import {
  Configuration, UserResourceApi,
} from '@/generated';

// TODO: get the path from the current active in the browser
const apiClientsCfg = new Configuration({
  basePath: "http://localhost:8080",
  baseOptions: {
    headers: {
      "Accept": "application/json",
      "Content-Type": "application/json"
    }
  }
});

export const userResourceApi = new UserResourceApi(apiClientsCfg);
