/*
  * Licensed to the Apache Software Foundation (ASF) under one
  * or more contributor license agreements.  See the NOTICE file
  * distributed with this work for additional information
  * regarding copyright ownership.  The ASF licenses this file
  * to you under the Apache License, Version 2.0 (the
  * "License"); you may not use this file except in compliance
  * with the License.  You may obtain a copy of the License at
  *
  *     http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */

import type { RouteRecordRaw } from 'vue-router'
import { createRouter, createWebHashHistory  } from 'vue-router'
const SwaggerUI = () => import('@/components/SwaggerUI.vue')
const Home = () => import('@/views/Home.vue')
const Page404 = () => import('@/views/404.vue')
const Catalogs = () => import('@/views/catalogs/index.vue')
const Tables = () => import('@/views/tables/index.vue')
const HiveTables = () => import('@/views/hive-details/index.vue')
const UpgradeTable = () => import('@/views/hive-details/upgrade.vue')
const CreateTable = () => import('@/views/tables/create.vue')
// const Optimizing = () => import('@/views/optimize/index.vue')
const Settings = () => import('@/views/settings/index.vue')
const Terminal = () => import('@/views/terminal/index.vue')
const Login = () => import('@/views/login/index.vue')
const Resource = () => import('@/views/resource/index.vue')
const Overview = () => import('@/views/overview/index.vue')

const routes: Array<RouteRecordRaw> = [
  {
    path: '/',
    name: 'Home',
    redirect: 'overview', // overview
    component: Home,
    children: [
      {
        path: 'catalogs',
        name: 'Catalogs',
        component: Catalogs,
      },
      {
        path: 'tables',
        name: 'Tables',
        component: Tables,
        children: [
          {
            path: 'create',
            name: 'Create',
            component: CreateTable,
          },
        ],
      },
      {
        path: 'hive-tables',
        name: 'HiveTables',
        component: HiveTables,
        children: [
          {
            path: 'upgrade',
            name: 'Upgrade',
            component: UpgradeTable,
          },
        ],
      },
      {
        path: 'optimizing',
        name: 'Optimizing',
        component: Resource,
      },
      {
        path: 'settings',
        name: 'Settings',
        component: Settings,
      },
      {
        path: 'terminal',
        name: 'Terminal',
        component: Terminal,
      },
      {
        path: 'overview',
        name: 'Overview',
        component: Overview,
      },
      // {
      //   path: 'resource',
      //   name: 'Resource',
      //   component: Resource
      // }
    ],
  },
  {
    path: '/login',
    name: 'Login',
    component: Login,
  },
  {
    path: '/openapi-ui',
    name: 'SwaggerUI',
    component: SwaggerUI,
  },
  {
    path: '/404',
    name: 'Page404',
    component: Page404,
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'Page404',
    component: Page404,
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

export default router
