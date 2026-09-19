#!/bin/bash

set -e

INPUT_FILE="./openapi.yaml";
OUTPUT_TMP_FILE="/tmp/be_open_api.yaml";
OUTPUT_DIR_PATH="./src/generated";

# Preserve the original
cp ${INPUT_FILE} ${OUTPUT_TMP_FILE};

# when on a Mac
if [ "$(uname)" == "Darwin" ]; then
  sed -i '' "s/13:45:30.123456789//" ${OUTPUT_TMP_FILE};
# when not on a Mac
else
  sed -i "s/13:45:30.123456789//" ${OUTPUT_TMP_FILE};
fi

rm -fr ${OUTPUT_DIR_PATH};

# https://openapi-generator.tech/docs/generators/typescript-axios/
openapi-generator-cli generate -i ${OUTPUT_TMP_FILE} \
  -g typescript-axios \
  -p useSingleRequestParameter=true \
  -p withSeparateModelsAndApi=true \
  -p apiPackage=apis \
  -p modelPackage=models \
  --global-property models,apis,supportingFiles,apiDocs=false,modelDocs=false,apiTests=false,modelTests=false \
  -o ${OUTPUT_DIR_PATH}

rm -rf ${OUTPUT_DIR_PATH}/.openapi-generator
rm -f ${OUTPUT_DIR_PATH}/.gitignore
rm -f ${OUTPUT_DIR_PATH}/.npmignore
rm -f ${OUTPUT_DIR_PATH}/README.md
rm -f ${OUTPUT_DIR_PATH}/git_push.sh

#EOF