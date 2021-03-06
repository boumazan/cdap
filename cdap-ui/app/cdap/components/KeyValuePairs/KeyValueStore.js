/*
* Copyright © 2016 Cask Data, Inc.
*
* Licensed under the Apache License, Version 2.0 (the "License"); you may not
* use this file except in compliance with the License. You may obtain a copy of
* the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
* WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
* License for the specific language governing permissions and limitations under
* the License.
*/

import {combineReducers, createStore} from 'redux';
import KeyValueStoreActions from './KeyValueStoreActions';
var shortid = require('shortid');

const defaultAction = {
  type: '',
  payload: {}
};

const keyValues = (state = [], action=defaultAction) => {
  let stateCopy;
  switch (action.type) {
    case KeyValueStoreActions.setKey:
      stateCopy = Object.assign({}, state);
      if (action.payload.key === null || typeof action.payload.key === 'undefined') {
        return stateCopy;
      }
      stateCopy.pairs[action.payload.index].key = action.payload.key;
      return stateCopy;
    case KeyValueStoreActions.setVal:
      stateCopy = Object.assign({}, state);
      if (action.payload.value === null || typeof action.payload.value === 'undefined') {
        return stateCopy;
      }
      stateCopy.pairs[action.payload.index].value = action.payload.value;
      return stateCopy;
    case KeyValueStoreActions.addPair:
      stateCopy = Object.assign({}, state);
      stateCopy.pairs.splice(action.payload.index + 1, 0, {
        key : '',
        value: '',
        uniqueId: shortid.generate()
      });
      return stateCopy;
    case KeyValueStoreActions.deletePair:
      stateCopy = Object.assign({}, state);
      stateCopy.pairs.splice(action.payload.index, 1);
      if (!stateCopy.pairs.length) {
        stateCopy.pairs.push({
          key : '',
          value : '',
          uniqueId: shortid.generate()
        });
      }
      return stateCopy;
    case KeyValueStoreActions.onReset:
      return [];
    case KeyValueStoreActions.onUpdate:
      stateCopy = Object.assign({}, state);
      stateCopy.pairs = action.payload.pairs;
      return stateCopy;
    default:
      return state;
  }
};

const createKeyValueStore = (initialState = []) => {
  return createStore(
    combineReducers({keyValues}),
    initialState,
    window.devToolsExtension ? window.devToolsExtension() : f => f
  );
};

export { createKeyValueStore };
