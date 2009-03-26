/**
 * Copyright (C) 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.inject;

import com.google.inject.internal.ImmutableList;
import com.google.inject.internal.ImmutableMap;
import com.google.inject.internal.Lists;
import com.google.inject.matcher.Matcher;
import com.google.inject.matcher.Matchers;
import static com.google.inject.matcher.Matchers.any;
import com.google.inject.spi.InjectableType;
import com.google.inject.spi.InjectableType.Encounter;
import com.google.inject.spi.InjectionListener;
import static com.google.inject.Asserts.assertContains;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import junit.framework.TestCase;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

/**
 * @author jessewilson@google.com (Jesse Wilson)
 */
public class InjectableTypeListenerTest extends TestCase {

  private static MethodInterceptor prefixInterceptor(final String prefix) {
    return new MethodInterceptor() {
      public Object invoke(MethodInvocation methodInvocation) throws Throwable {
        return prefix + methodInvocation.proceed();
      }
    };
  }

  public void testTypeListenersAreFired() throws NoSuchMethodException {
    final Constructor<A> aConstructor = A.class.getDeclaredConstructor();
    final AtomicInteger firedCount = new AtomicInteger();

    final InjectableType.Listener typeListener = new InjectableType.Listener() {
      public <I> void hear(InjectableType<I> injectableType, Encounter<I> encounter) {
        assertEquals(new TypeLiteral<A>() {}, injectableType.getType());
        assertEquals(aConstructor, injectableType.getInjectableConstructor().getMember());
        assertEquals(2, injectableType.getInjectableMembers().size());
        assertEquals(ImmutableMap.<Method, List<MethodInterceptor>>of(),
            injectableType.getMethodInterceptors());
        firedCount.incrementAndGet();
      }
    };

    Guice.createInjector(new AbstractModule() {
      protected void configure() {
        bindListener(any(), typeListener);
        bind(A.class);
      }
    });

    assertEquals(1, firedCount.get());
  }

  public void testInstallingInjectionListener() {
    final List<Object> injectees = Lists.newArrayList();
    final InjectionListener<Object> injectionListener = new InjectionListener<Object>() {
      public void afterInjection(Object injectee) {
        injectees.add(injectee);
      }
    };

    Injector injector = Guice.createInjector(new AbstractModule() {
      protected void configure() {
        bindListener(any(), new InjectableType.Listener() {
          public <I> void hear(InjectableType<I> injectableType, Encounter<I> encounter) {
            encounter.register(injectionListener);
          }
        });
        bind(A.class);
      }
    });

    assertEquals(ImmutableList.of(), injectees);

    Object a1 = injector.getInstance(A.class);
    assertEquals(ImmutableList.of(a1), injectees);

    Object a2 = injector.getInstance(A.class);
    assertEquals(ImmutableList.of(a1, a2), injectees);

    Object b1 = injector.getInstance(B.class);
    assertEquals(ImmutableList.of(a1, a2, b1), injectees);

    Provider<A> aProvider = injector.getProvider(A.class);
    assertEquals(ImmutableList.of(a1, a2, b1), injectees);
    A a3 = aProvider.get();
    A a4 = aProvider.get();
    assertEquals(ImmutableList.of(a1, a2, b1, a3, a4), injectees);
  }
  
  public void testAddingInterceptors() throws NoSuchMethodException {
    final Matcher<Object> buzz = Matchers.only(C.class.getMethod("buzz"));

    Injector injector = Guice.createInjector(new AbstractModule() {
      protected void configure() {
        bindInterceptor(any(), buzz, prefixInterceptor("ka"));
        bindInterceptor(any(), any(), prefixInterceptor("fe"));

        bindListener(any(), new InjectableType.Listener() {
          public <I> void hear(InjectableType<I> injectableType, Encounter<I> encounter) {
            encounter.bindInterceptor(any(), prefixInterceptor("li"));
            encounter.bindInterceptor(buzz, prefixInterceptor("no"));
          }
        });
      }
    });

    // interceptors must be invoked in the order they're bound.
    C c = injector.getInstance(C.class);
    assertEquals("kafelinobuzz", c.buzz());
    assertEquals("felibeep", c.beep());
  }

  public void testInjectableTypeListenerThrows() {
    final InjectableType.Listener clumsyListener = new InjectableType.Listener() {
      int failures = 0;

      public <I> void hear(InjectableType<I> injectableType, Encounter<I> encounter) {
        throw new ClassCastException("whoops, failure #" + (++failures));
      }

      @Override public String toString() {
        return "clumsy";
      }
    };

    try {
      Guice.createInjector(new AbstractModule() {
        protected void configure() {
          bindListener(any(), clumsyListener);
          bind(B.class);
          bind(C.class);
        }
      });
      fail();
    } catch (CreationException expected) {
      assertContains(expected.getMessage(),
          "1) Error notifying InjectableType.Listener clumsy (bound at " + getClass().getName(),
          ".configure(InjectableTypeListenerTest.java:",
          "of " + B.class.getName(), 
          "Reason: java.lang.ClassCastException: whoops, failure #1",
          "2) Error notifying InjectableType.Listener clumsy (bound at " + getClass().getName(),
          ".configure(InjectableTypeListenerTest.java:",
          "of " + C.class.getName(),
          "Reason: java.lang.ClassCastException: whoops, failure #2");
    }
    
    Injector injector = Guice.createInjector(new AbstractModule() {
      protected void configure() {
        bindListener(any(), clumsyListener);
      }
    });
    try {
      injector.getProvider(B.class);
      fail();
    } catch (ConfigurationException expected) {
      assertContains(expected.getMessage(),
          "1) Error notifying InjectableType.Listener clumsy (bound at " + getClass().getName(),
          ".configure(InjectableTypeListenerTest.java:",
          "of " + B.class.getName(),
          "Reason: java.lang.ClassCastException: whoops, failure #3");
    }

    // getting it again should yield the same exception #3
    try {
      injector.getInstance(B.class);
      fail();
    } catch (ConfigurationException expected) {
      assertContains(expected.getMessage(),
          "1) Error notifying InjectableType.Listener clumsy (bound at " + getClass().getName(),
          ".configure(InjectableTypeListenerTest.java:",
          "of " + B.class.getName(),
          "Reason: java.lang.ClassCastException: whoops, failure #3");
    }

    // non-constructed types do not participate
    assertSame(Stage.DEVELOPMENT, injector.getInstance(Stage.class));
  }

  public void testInjectionListenerThrows() {
    final InjectionListener<Object> injectionListener = new InjectionListener<Object>() {
      int failures = 0;

      public void afterInjection(Object injectee) {
        throw new ClassCastException("whoops, failure #" + (++failures));
      }

      @Override public String toString() {
        return "goofy";
      }
    };

    Injector injector = Guice.createInjector(new AbstractModule() {
      protected void configure() {
        bindListener(any(), new InjectableType.Listener() {
          public <I> void hear(InjectableType<I> injectableType, Encounter<I> encounter) {
            encounter.register(injectionListener);
          }
        });
        bind(B.class);
      }
    });

    try {
      injector.getInstance(A.class);
      fail();
    } catch (ProvisionException e) {
      assertContains(e.getMessage(),
          "1) Error notifying InjectionListener goofy of " + A.class.getName(),
          " Reason: java.lang.ClassCastException: whoops, failure #1");
    }

    // second time through should be a new cause (#2)
    try {
      injector.getInstance(A.class);
      fail();
    } catch (ProvisionException e) {
      assertContains(e.getMessage(),
          "1) Error notifying InjectionListener goofy of " + A.class.getName(),
          " Reason: java.lang.ClassCastException: whoops, failure #2");
    }

    // we should get errors for all types, but only on getInstance()
    Provider<B> bProvider = injector.getProvider(B.class);
    try {
      bProvider.get();
      fail();
    } catch (ProvisionException e) {
      assertContains(e.getMessage(),
          "1) Error notifying InjectionListener goofy of " + B.class.getName(),
          " Reason: java.lang.ClassCastException: whoops, failure #3");
    }

    // non-constructed types do not participate
    assertSame(Stage.DEVELOPMENT, injector.getInstance(Stage.class));
  }

  static class A {
    @Inject Injector injector;
    @Inject Stage stage;
  }

  static class B {}

  public static class C {
    public String buzz() {
      return "buzz";
    }

    public String beep() {
      return "beep";
    }
  }
}