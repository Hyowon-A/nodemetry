<script>
  import { onMount } from 'svelte';
  import { env } from '$env/dynamic/public';
  import { startMockFeed } from '$lib/telemetry.svelte.js';
  import { connectLive, disconnectLive } from '$lib/live.svelte.js';
  import '../app.css';

  let { children } = $props();

  onMount(() => {
    if (env.PUBLIC_USE_MOCK === 'false') {
      connectLive();
      return disconnectLive;
    }
    return startMockFeed();
  });
</script>

{@render children()}
